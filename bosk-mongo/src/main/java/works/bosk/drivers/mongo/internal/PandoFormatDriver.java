package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.lang.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.NonNull;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.Entity;
import works.bosk.EnumerableByIdentifier;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.exceptions.FormatMisconfigurationException;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NotYetImplementedException;

import static com.mongodb.ReadConcern.LOCAL;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.newSetFromMap;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.bson.BsonBoolean.TRUE;
import static works.bosk.Path.parseParameterized;
import static works.bosk.drivers.mongo.internal.BsonFormatter.docBsonPath;
import static works.bosk.util.Classes.enumerableByIdentifier;

/**
 * Implements {@link PandoFormat}.
 */
final class PandoFormatDriver<R extends StateTreeNode> extends AbstractFormatDriver<R> {
	private final String description;
	private final PandoFormat format;
	private final MongoDriverSettings settings;
	private final TransactionalCollection<BsonDocument> collection;
	private final BoskDriver downstream;
	private final FlushLock flushLock;
	private final BsonSurgeon bsonSurgeon;
	private final Demultiplexer demultiplexer = new Demultiplexer();
	private final List<Reference<? extends EnumerableByIdentifier<?>>> graftPoints;

	private volatile BsonInt64 revisionToSkip = null;

	static final BsonString ROOT_DOCUMENT_ID = new BsonString("|");

	PandoFormatDriver(
		BoskInfo<R> boskInfo,
		TransactionalCollection<BsonDocument> collection,
		MongoDriverSettings driverSettings,
		PandoFormat format, BsonSerializer bsonSerializer,
		FlushLock flushLock,
		BoskDriver downstream
	) {
		super(boskInfo.rootReference(), boskInfo.diagnosticContext(), new Formatter(boskInfo, bsonSerializer));
		this.description = getClass().getSimpleName() + ": " + driverSettings;
		this.settings = driverSettings;
		this.format = format;
		this.collection = collection;
		this.downstream = downstream;
		this.flushLock = flushLock;
		this.graftPoints = format.graftPoints().stream()
			.map(s -> referenceTo(s, rootRef))
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.collect(toList());
		this.bsonSurgeon = new BsonSurgeon(graftPoints);
	}

	private static Reference<EnumerableByIdentifier<Entity>> referenceTo(String pathString, RootReference<?> rootRef) {
		try {
			return rootRef.then(enumerableByIdentifier(Entity.class), parseParameterized(pathString));
		} catch (InvalidTypeException e) {
			throw new FormatMisconfigurationException("Path does not point to a Catalog or SideTable: " + pathString, e);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		doReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		collection.ensureTransactionStarted();
		Reference<?> mainRef = mainRef(target);
		BsonDocument filter = documentFilter(mainRef)
			.append(BsonFormatter.dottedFieldNameOf(target, mainRef), new BsonDocument("$exists", TRUE));
		if (documentExists(filter)) {
			LOGGER.debug("Already exists: {}", filter);
			collection.abortTransaction();
			return;
		}
		doReplacement(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		doDelete(target);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		collection.ensureTransactionStarted();
		if (preconditionFailed(precondition, requiredValue)) {
			collection.abortTransaction();
			return;
		}
		doReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		collection.ensureTransactionStarted();
		if (preconditionFailed(precondition, requiredValue)) {
			collection.abortTransaction();
			return;
		}
		doDelete(target);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		flushLock.awaitRevision(readRevisionNumber());
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		flushLock.close();
	}

	@Override
	public StateAndMetadata<R> loadAllState() throws UninitializedCollectionException, IOException {
		BsonState bsonState = loadBsonState();

		R root;
		if (bsonState.state() == null) {
			throw new IOException("No existing state in document");
		} else {
			root = formatter.document2object(bsonState.state(), rootRef);
		}
		MapValue<String> diagnosticAttributes = bsonState.diagnosticAttributes() == null ? null
			:formatter.decodeDiagnosticAttributes(bsonState.diagnosticAttributes());
		return new StateAndMetadata<>(
			root,
			bsonState.revision() == null? Formatter.REVISION_ZERO : bsonState.revision(),
			diagnosticAttributes);
	}

	@Override
	BsonState loadBsonState() throws UninitializedCollectionException {
		List<BsonDocument> allParts = new ArrayList<>();
		try (MongoCursor<BsonDocument> cursor = collection
			.withReadConcern(LOCAL) // The revision field needs to be the latest
			.find(regex("_id", "^" + Pattern.quote("|")))
			.sort(new BsonDocument("_id", new BsonInt32(-1))) // Root doc last
			.cursor()
		) {
			while (cursor.hasNext()) {
				allParts.add(cursor.next());
			}
		} catch (NoSuchElementException e) {
			throw new UninitializedCollectionException("No existing document", e);
		}
		BsonDocument mainPart = allParts.getLast();
		if (!ROOT_DOCUMENT_ID.equals(mainPart.get("_id"))) {
			throw new IllegalStateException("Cannot locate root document");
		}

		return new BsonState(
			bsonSurgeon.gather(allParts),
			mainPart.getInt64(Formatter.DocumentFields.revision.name(), null), Formatter.getDiagnosticAttributesIfAny(mainPart)
		);
	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) {
		BsonValue initialState = formatter.object2bsonValue(priorContents.state(), rootRef.targetType());
		BsonInt64 newRevision = new BsonInt64(1 + priorContents.revision().longValue());
		// Note that priorContents.diagnosticAttributes are ignored, and we use the attributes from this thread

		LOGGER.debug("** Initial upsert for {}", ROOT_DOCUMENT_ID.getValue());
		collection.ensureTransactionStarted();
		if (initialState instanceof BsonDocument) {
			upsertAndRemoveSubParts(rootRef, initialState.asDocument()); // Mutates initialState!
		}
		BsonDocument update = new BsonDocument("$set", initialDocument(initialState, newRevision));
		BsonDocument filter = rootDocumentFilter();
		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result = collection.updateOne(filter, update, options);
		LOGGER.debug("| Result: {}", result);
		writeManifest();
	}

	private void writeManifest() {
		BsonDocument doc = new BsonDocument("_id", MainDriver.MANIFEST_ID);
		doc.putAll((BsonDocument) formatter.object2bsonValue(Manifest.forPando(format), Manifest.class));
		BsonDocument filter = new BsonDocument("_id", MainDriver.MANIFEST_ID);
		LOGGER.debug("| Initial manifest: {}", doc);
		ReplaceOptions options = new ReplaceOptions().upsert(true);
		UpdateResult result = collection.replaceOne(filter, doc, options);
		LOGGER.debug("| Manifest result: {}", result);
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection}.
	 */
	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		assert event.getDocumentKey() != null;
		BsonValue bsonDocumentID = event.getDocumentKey().get("_id");
		if (MainDriver.MANIFEST_ID.equals(bsonDocumentID)) {
			onManifestEvent(event);
			return;
		}
		if (!(bsonDocumentID instanceof BsonString s) || !(s.getValue().startsWith("|"))) {
			LOGGER.debug("Ignoring event for unrecognized document key: {} type {}", event.getDocumentKey(), bsonDocumentID.getClass());
			return;
		}

		// This is an event we care about

		if (event.getTxnNumber() == null) {
			LOGGER.debug("Processing standalone event {} on {}", event.getOperationType(), event.getDocumentKey());
			processTransaction(singletonList(event));
		} else {
			demultiplexer.add(event);
			if (isFinalEventOfTransaction(event)) {
				LOGGER.debug("Processing final event {} on {}", event.getOperationType(), event.getDocumentKey());
				processTransaction(demultiplexer.pop(event));
			} else {
				LOGGER.debug("Queueing transaction event {} on {}", event.getOperationType(), event.getDocumentKey());
			}
		}
	}

	/**
	 * The final event updates the revision field of the root document.
	 */
	private boolean isFinalEventOfTransaction(ChangeStreamDocument<BsonDocument> event) {
		return
			ROOT_DOCUMENT_ID.equals(event.getDocumentKey().get("_id"))
				&& updateEventHasField(event, Formatter.DocumentFields.revision);
	}

	private void processTransaction(List<ChangeStreamDocument<BsonDocument>> events) throws UnprocessableEventException {
		ChangeStreamDocument<BsonDocument> finalEvent = events.getLast();
		switch (finalEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				BsonDocument fullDocument = finalEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on final event", finalEvent.getOperationType());
				}

				BsonInt64 revision = formatter.getRevisionFromFullDocument(fullDocument);
				if (shouldSkip(revision)) {
					LOGGER.debug("Skipping revision {}", revision.longValue());
					return;
				}

				MapValue<String> diagnosticAttributes = formatter.eventDiagnosticAttributesFromFullDocument(fullDocument);
				try (var _ = diagnosticContext.withOnly(diagnosticAttributes)) {
					BsonDocument state = fullDocument.getDocument(Formatter.DocumentFields.state.name());
					if (state == null) {
						ChangeStreamDocument<BsonDocument> mainEvent = events.get(events.size() - 2);
						LOGGER.debug("Main event is {} on {}", mainEvent.getOperationType(), mainEvent.getDocumentKey());
						propagateDownstream(mainEvent, events.subList(0, events.size() - 2));
					} else {
						LOGGER.debug("Main event is final event");
						propagateDownstream(finalEvent, events.subList(0, events.size() - 1));
					}
				}

				flushLock.finishedRevision(revision);
			} break;
			case UPDATE: {
				// TODO: Combine code with INSERT and REPLACE events
				BsonInt64 revision = formatter.getRevisionFromUpdateEvent(finalEvent);
				if (shouldSkip(revision)) {
					LOGGER.debug("Skipping revision {}", revision.longValue());
					return;
				}
				MapValue<String> attributes = formatter.eventDiagnosticAttributesFromUpdate(finalEvent);
				try (var _ = diagnosticContext.withOnly(attributes)) {
					boolean mainEventIsFinalEvent = updateEventHasField(finalEvent, Formatter.DocumentFields.state); // If the final update changes only the revision field, then it's not the main event
					if (mainEventIsFinalEvent) {
						LOGGER.debug("Main event is final event");
						propagateDownstream(finalEvent, events.subList(0, events.size() - 1));
					} else if (events.size() < 2) {
						LOGGER.debug("Main event is a no-op");
					} else {
						ChangeStreamDocument<BsonDocument> mainEvent = events.get(events.size() - 2);
						LOGGER.debug("Main event is {} on {}", mainEvent.getOperationType(), mainEvent.getDocumentKey());
						propagateDownstream(mainEvent, events.subList(0, events.size() - 2));
					}
				}
				flushLock.finishedRevision(revision);
			} break;
			case DELETE: {
				// No other events in the transaction matter if the root document is gone
				LOGGER.debug("Document containing revision field has been deleted; assuming revision=0");
				flushLock.finishedRevision(Formatter.REVISION_ZERO);
				revisionToSkip = null;
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", finalEvent.getOperationType());
			}
		}
	}

	private void propagateDownstream(ChangeStreamDocument<BsonDocument> mainEvent, List<ChangeStreamDocument<BsonDocument>> priorEvents) throws UnprocessableEventException {
		switch (mainEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				BsonDocument fullDocument = mainEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on main event", mainEvent.getOperationType());
				}

				BsonDocument state = fullDocument.getDocument(Formatter.DocumentFields.state.name(), null);
				if (state == null) {
					throw new UnprocessableEventException("Missing state field", mainEvent.getOperationType());
				}

				Reference<?> mainRef;
				BsonDocument bsonState;
				if (priorEvents == null) {
					LOGGER.debug("No prior events");
					bsonState = state;
					mainRef = documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent);
				} else {
					LOGGER.debug("{} prior events", priorEvents.size());
					List<BsonDocument> parts = subpartDocuments(priorEvents);
					parts.add(fullDocument);
					bsonState = bsonSurgeon.gather(parts);
					mainRef = documentID2MainRef(fullDocument.getString("_id").getValue(), mainEvent);
				}

				LOGGER.debug("| Replace downstream {}", mainRef);
				submitReplacementDownstream(mainRef, bsonState);
			} break;
			case UPDATE: {
				Reference<?> mainRef = documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent);
				UpdateDescription updateDescription = mainEvent.getUpdateDescription();
				if (updateDescription != null) {
					replaceUpdatedFields(mainRef, updateDescription.getUpdatedFields(), subpartDocuments(priorEvents), mainEvent.getOperationType());
					deleteRemovedFields(mainRef, updateDescription.getRemovedFields(), mainEvent.getOperationType());
				}
			} break;
			case DELETE: {
				// No other events in the transaction matter if the main document is deleted
				Reference<?> mainRef = mainRef(documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent));
				LOGGER.debug("| Delete downstream {}", mainRef);
				downstream.submitDeletion(mainRef);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", mainEvent.getOperationType());
			}
		}
	}

	private List<BsonDocument> subpartDocuments(List<ChangeStreamDocument<BsonDocument>> priorEvents) {
		return priorEvents.stream()
			.filter(e -> OPERATIONS_TO_INCLUDE_IN_GATHER.contains(e.getOperationType()))
			.map(this::fullDocumentForSubPart)
			.collect(toCollection(ArrayList::new));
	}

	private @NonNull BsonDocument fullDocumentForSubPart(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument result = event.getFullDocument();
		if (result == null) {
			throw new IllegalStateException("No full document in change stream event for subpart: " + event.getOperationType() + " on " + event.getDocumentKey());
		}
		return result;
	}

	/**
	 * This lets us use Java generics to avoid some ugly typecasts
	 */
	private <T> void submitReplacementDownstream(Reference<T> mainRef, BsonDocument bsonState) {
		T newValue = formatter.document2object(bsonState, mainRef);
		downstream.submitReplacement(mainRef, newValue);
	}

	private Reference<?> documentID2MainRef(String pipedPath, ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		// referenceTo does everything we need already. Build a fake dotted field name and use that
		String dottedName = "state" + pipedPath.replace('|', '.');
		try {
			return BsonFormatter.referenceTo(dottedName, rootRef);
		} catch (InvalidTypeException e) {
			throw new UnprocessableEventException("Invalid path from document ID: \"" + pipedPath + "\"", e, event.getOperationType());
		}
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection},
	 * but outside that, we want to be as strict as possible
	 * so incompatible database changes don't go unnoticed.
	 */
	private void onManifestEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		LOGGER.debug("onManifestEvent({})", event.getOperationType().name());
		if (event.getOperationType() == INSERT || event.getOperationType() == REPLACE) {
			BsonDocument manifestDoc = requireNonNull(event.getFullDocument());
			Manifest manifest;
			try {
				manifest = formatter.decodeManifest(manifestDoc);
			} catch (UnrecognizedFormatException e) {
				throw new UnprocessableEventException("Invalid manifest", e, event.getOperationType());
			}
			if (!manifest.equals(Manifest.forPando(format))) {
				throw new UnprocessableEventException("Manifest indicates format has changed", event.getOperationType());
			}
		} else {
			// PandoFormatDriver always uses INSERT/REPLACE to update the manifest;
			// anything else is unexpected.
			throw new UnprocessableEventException("Unexpected change to manifest document", event.getOperationType());
		}
		LOGGER.debug("Ignoring benign manifest change event");
	}

	@Override
	public void onRevisionToSkip(BsonInt64 revision) {
		LOGGER.debug("+ onRevisionToSkip({})", revision.longValue());
		revisionToSkip = revision;
		flushLock.finishedRevision(revision);
	}

	private static boolean updateEventHasField(ChangeStreamDocument<BsonDocument> event, Formatter.DocumentFields field) {
		if (event == null) {
			return false;
		}

		BsonDocument updatedFields;
		switch (event.getOperationType()) {
			case UPDATE -> {
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription == null) {
					return false;
				}
				updatedFields = updateDescription.getUpdatedFields();

				// This catches the case of somebody deleting the field manually
				List<String> removedFields = updateDescription.getRemovedFields();
				if (removedFields != null) {
					if (removedFields.stream().anyMatch(k -> k.startsWith(field.name()))) {
						return true;
					}
				}
			}
			case INSERT -> {
				// A change that was submitted as an "update" but was actually an upsert
				// will transmute into an insert if the document didn't exist, so we need
				// to handle those too.
				updatedFields = event.getFullDocument();
			}
			default -> {
				return false;
			}
		}

		if (updatedFields == null) {
			return false;
		}

		return updatedFields.keySet().stream().anyMatch(k -> k.startsWith(field.name()));
	}

	//
	// MongoDB helpers
	//

	/**
	 * <em>Implementation note</em>: While {@link SequoiaFormatDriver} has calls like <code>doUpdate</code>,
	 * Pando uses <code>doReplacement</code> and <code>doDelete</code>.
	 * The reason is that Sequoia, being a single-document format, can implement all its preconditions
	 * (be they conditional updates, or existence of containing objects, etc.) as query filters,
	 * and so can do every update (replacements and deletions) as a single document update operation.
	 * In contrast, Pando implements its preconditions using read operations and actual if statements in the code,
	 * and so the actual replacement/deletion is unconditional, because it won't be executed if the precondition fails.
	 * <p>
	 * TODO: We could organize the Sequoia code in a similar manner if doReplacement and doDelete
	 * accepted filter arguments (which Pando simply wouldn't use).
	 */
	private <T> void doReplacement(Reference<T> target, T newValue) {
		collection.ensureTransactionStarted();
		LOGGER.debug("doReplacement({})", target);
		Reference<?> mainRef = mainRef(target);
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		if (value instanceof BsonDocument b) {
			deletePartsUnder(target);
			upsertAndRemoveSubParts(target, b);
			// Note that value will now have the sub-parts removed
		}
		if (rootRef.equals(mainRef)) {
			LOGGER.debug("| Root ref is main ref");
			LOGGER.debug("| Pre-delete on root document");
			String key = BsonFormatter.dottedFieldNameOf(target, rootRef);
			LOGGER.debug("| Pre-delete field {}", key);
			doUpdate( // Important: don't bump the revision field because that's how we identify the last event in a transaction
				new BsonDocument("$unset", new BsonDocument(key, BsonNull.VALUE)),
				standardRootPreconditions(target));
			LOGGER.debug("| Update root document");
			doUpdate(replacementDoc(target, value, rootRef), standardRootPreconditions(target));
		} else {
			// Note: don't use mainPart's ID. TODO: Is this ok? Why is the ID wrong?
			BsonDocument filter = documentFilter(mainRef);
			if (target.equals(mainRef)) {
				// Upsert the main doc
				// TODO: merge this with the same code in upsertAndRemoveSubParts
				LOGGER.debug("| Pre-delete main document");
				collection.deleteOne(filter);

				LOGGER.debug("| Update main document");
				BsonDocument update = new BsonDocument("$set",
					filter.clone()
						.append(Formatter.DocumentFields.state.name(), value));
				LOGGER.debug("| Update: {}", update);
				LOGGER.debug("| Filter: {}", filter);
				collection.updateOne(filter, update, new UpdateOptions().upsert(true));

				// Move up to the parent document to set the "true" stub
				mainRef = mainRef(mainRef.enclosingReference(Object.class));
				filter = documentFilter(mainRef);
				value = TRUE;
				LOGGER.debug("| Move up to enclosing main reference {}", mainRef);
			}

			// Update part of the main doc (which must already exist)
			String key = BsonFormatter.dottedFieldNameOf(target, mainRef);
			LOGGER.debug("| Pre-delete field {} in {}", key, mainRef);
			BsonDocument preDelete = new BsonDocument("$unset", new BsonDocument(key, BsonNull.VALUE));
			doUpdate(preDelete, standardPreconditions(target, mainRef, filter));
			LOGGER.debug("| Set field {} in {}: {}", key, mainRef, value);
			BsonDocument mainUpdate = new BsonDocument("$set", new BsonDocument(key, value));
			doUpdate(mainUpdate, standardPreconditions(target, mainRef, filter));

			LOGGER.debug("| Bump revision on root document");
			doUpdate(blankUpdateDoc(), rootDocumentFilter());
		}
	}

	private <T> void doDelete(Reference<T> target) {
		collection.ensureTransactionStarted();
		deletePartsUnder(target);
		Reference<?> mainRef = mainRef(target);
		if (mainRef.equals(target)) {
			// Delete the whole document
			if (settings.experimental().orphanDocumentMode() == MongoDriverSettings.OrphanDocumentMode.HASTY) {
				LOGGER.debug("Skipping deleting document({}) in {} mode", target, MongoDriverSettings.OrphanDocumentMode.HASTY);
			} else {
				throw new NotYetImplementedException("Earnest mode not yet implemented");
			}

			assert !mainRef.path().isEmpty(): "Can't delete the root reference";
			// Move up to the parent document to delete the "true" stub
			mainRef = mainRef(mainRef.enclosingReference(Object.class));
			LOGGER.debug("Move up to enclosing main reference {}", mainRef);
		}
		if (doUpdate(deletionDoc(target, mainRef), standardPreconditions(target, mainRef, documentFilter(mainRef)))) {
			if (!rootRef.equals(mainRef)) {
				LOGGER.debug("Deletion succeeded; bumping revision number in root document");
				doUpdate(blankUpdateDoc(), rootDocumentFilter());
			}
		} else {
			LOGGER.debug("Deletion had no effect; aborting transaction");
			collection.abortTransaction();
		}
	}

	private boolean preconditionFailed(Reference<Identifier> precondition, Identifier requiredValue) {
		Reference<?> mainRef = mainRef(precondition);
		BsonDocument filter = documentFilter(mainRef)
			.append(BsonFormatter.dottedFieldNameOf(precondition, mainRef), new BsonString(requiredValue.toString()));
		LOGGER.debug("Precondition filter: {}", filter);
		boolean result = !documentExists(filter);
		if (result) {
			LOGGER.debug("Precondition fail: {} != {}", precondition, requiredValue);
		}
		return result;
	}

	private boolean documentExists(BsonDocument filter) {
		return 0 != collection.countDocuments(filter, new CountOptions().limit(1));
	}

	/**
	 * @return {@link Reference} to the bosk object corresponding to the document
	 * that contains the given <code>target</code>.
	 */
	private Reference<?> mainRef(Reference<?> target) {
		if (target.path().isEmpty()) {
			return rootRef;
		}

		// The main reference is the "deepest" one that matches the target reference.
		// graftPoints is in descending order of depth.
		// TODO: This could be done more efficiently, perhaps using a trie
		int targetPathLength = target.path().length();
		for (var candidateContainer: graftPoints) {
			int containerPathLength = candidateContainer.path().length();
			if (containerPathLength <= targetPathLength - 1) {
				if (candidateContainer.path().matchesPrefixOf(target.path())) {
					try {
						return candidateContainer.boundBy(target.path()).then(Object.class,
							// The container plus one segment from the target ref
							target.path().segment(containerPathLength)
						);
					} catch (InvalidTypeException e) {
						throw new AssertionError("Unexpected exception forming mainRef from container " + candidateContainer + " and target " + target);
					}
				}
			}
		}
		return rootRef;
	}

	/**
	 * @return Non-null revision number as per the database.
	 * If the database contains no revision number, returns {@link Formatter#REVISION_ZERO}.
	 */
	private BsonInt64 readRevisionNumber() throws FlushFailureException {
		LOGGER.debug("readRevisionNumber");
		try {
			try (MongoCursor<BsonDocument> cursor = collection
				.withReadConcern(LOCAL) // The revision field needs to be the latest
				.find(ROOT_DOCUMENT_FILTER)
				.limit(1)
				.projection(fields(Projections.include(Formatter.DocumentFields.revision.name())))
				.cursor()
			) {
				BsonDocument doc = cursor.next();
				BsonInt64 result = doc.getInt64(Formatter.DocumentFields.revision.name(), null);
				if (result == null) {
					// Document exists but has no revision field.
					// In that case, newer servers (including this one) will create the
					// the field upon initialization, and we're ok to wait for any old
					// revision number at all.
					LOGGER.debug("No revision field; assuming {}", Formatter.REVISION_ZERO.longValue());
					return Formatter.REVISION_ZERO;
				} else {
					LOGGER.debug("Read revision {}", result);
					return result;
				}
			}
		} catch (NoSuchElementException e) {
			LOGGER.debug("Document is missing", e);
			throw new RevisionFieldDisruptedException(e);
		} catch (RuntimeException e) {
			LOGGER.debug("readRevisionNumber failed", e);
			throw new FlushFailureException(e);
		}
	}

	private BsonDocument rootDocumentFilter() {
		return new BsonDocument("_id", ROOT_DOCUMENT_ID);
	}

	private BsonDocument documentFilter(Reference<?> docRef) {
		return new BsonDocument("_id", new BsonString(docBsonPath(docRef, rootRef)));
	}

	private <T> BsonDocument standardRootPreconditions(Reference<T> target) {
		return standardPreconditions(target, rootRef, rootDocumentFilter());
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target, Reference<?> startingRef, BsonDocument filter) {
		if (!target.path().equals(startingRef.path())) {
			String enclosingObjectKey = BsonFormatter.dottedFieldNameOf(target.enclosingReference(Object.class), startingRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
		}
		return filter;
	}

	private <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value, Reference<?> startingRef) {
		String key = BsonFormatter.dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Set field {}: {}", key, value);
		BsonDocument result = blankUpdateDoc();
		result.compute("$set", (_,existing) -> {
			if (existing == null) {
				return new BsonDocument(key, value);
			} else {
				return existing.asDocument().append(key, value);
			}
		});
		return result;
	}

	private <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
		String key = BsonFormatter.dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Unset field {}", key);
		return blankUpdateDoc().append("$unset", new BsonDocument(key, BsonNull.VALUE)); // Value is ignored
	}

	private BsonDocument blankUpdateDoc() {
		return new BsonDocument("$inc", new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonInt64(1)))
			.append("$set", new BsonDocument(Formatter.DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(diagnosticContext.getAttributes())));
	}

	private BsonDocument initialDocument(BsonValue initialState, BsonInt64 revision) {
		BsonDocument fieldValues = new BsonDocument("_id", ROOT_DOCUMENT_ID);

		fieldValues.put(Formatter.DocumentFields.path.name(), new BsonString("/"));
		fieldValues.put(Formatter.DocumentFields.state.name(), initialState);
		fieldValues.put(Formatter.DocumentFields.revision.name(), revision);
		fieldValues.put(Formatter.DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(diagnosticContext.getAttributes()));

		return fieldValues;
	}

	/**
	 * @return true if something changed
	 */
	private boolean doUpdate(BsonDocument updateDoc, BsonDocument filter) {
		LOGGER.debug("| Update: {}", updateDoc);
		LOGGER.debug("| Filter: {}", filter);
		UpdateResult result = collection.updateOne(filter, updateDoc);
		LOGGER.debug("| Update result: {}", result);
		if (result.wasAcknowledged()) {
			long matchedCount = result.getMatchedCount();
			if (matchedCount == 0) {
				LOGGER.debug("| -> No documents were updated; double-checking that the root document still exists");
				try (var cursor = collection.find(documentFilter(rootRef)).limit(1).cursor()) {
					if (!cursor.hasNext()) {
						throw new IllegalStateException("Root document disappeared");
					}
				}
			}
			return matchedCount >= 1;
		} else {
			LOGGER.error("MongoDB write was not acknowledged");
			LOGGER.trace("Details of MongoDB write not acknowledged:\n\tFilter: {}\n\tUpdate: {}\n\tResult: {}", filter, updateDoc, result);
			throw new IllegalStateException("Mongo write was not acknowledged: " + result);
		}
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitReplacement submitReplacement}</code>
	 * for each updated field.
	 */
	private void replaceUpdatedFields(Reference<?> mainRef, @Nullable BsonDocument updatedFields, List<BsonDocument> subParts, OperationType operationType) throws UnprocessableEventException {
		if (updatedFields != null) {
			boolean alreadyUsedSubparts = false;
			for (Map.Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(Formatter.DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = BsonFormatter.referenceTo(dottedName, mainRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}

					if (alreadyUsedSubparts) {
						throw new IllegalStateException("Not expecting an update event that changes multiple state fields");
					} else {
						alreadyUsedSubparts = true;
					}

					BsonValue replacementValue = entry.getValue();
					if (replacementValue instanceof BsonDocument) {
						LOGGER.debug("Replacement value is a document; gather along with {} subparts", subParts.size());
						String mainID = docBsonPath(ref, mainRef);
						BsonDocument mainDocument = new BsonDocument()
							.append("_id", new BsonString(mainID))
							.append("state", replacementValue);
						ArrayList<BsonDocument> parts = new ArrayList<>(subParts.size() + 1);
						parts.addAll(subParts);
						parts.add(mainDocument);

						replacementValue = bsonSurgeon.gather(parts);
					} else if (subParts.isEmpty()) {
						LOGGER.debug("Replacement value is scalar: {}", replacementValue);
					} else if (TRUE.equals(replacementValue)) {
						LOGGER.debug("Replacement value is stub; gather {} subparts", subParts.size());
						replacementValue = bsonSurgeon.gather(subParts);
					} else {
						throw new UnprocessableEventException("Scalar " + replacementValue + " has subparts:\n\t" + subParts, operationType);
					}

					LOGGER.debug("| Replace {}", ref);
					LOGGER.trace("| New value: {}", replacementValue);
					Object replacement = formatter.bsonValue2object(replacementValue, ref);
					downstream.submitReplacement(ref, replacement);
					LOGGER.trace("| Done replacing {}", ref);
				}
			}
		}
	}

	private boolean shouldSkip(BsonInt64 revision) {
		return revision != null && revisionToSkip != null && revision.longValue() <= revisionToSkip.longValue();
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(Reference<?> mainRef, @Nullable List<String> removedFields, OperationType operationType) throws UnprocessableEventException {
		if (removedFields != null) {
			for (String dottedName : removedFields) {
				if (dottedName.startsWith(Formatter.DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = BsonFormatter.referenceTo(dottedName, mainRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}
					LOGGER.debug("| Delete {}", ref);
					downstream.submitDeletion(ref);
				} else {
					throw new UnprocessableEventException("Deletion of metadata field " + dottedName, operationType);
				}
			}
		}
	}

	private <T> void deletePartsUnder(Reference<T> target) {
		// This whole method is pretty "best-effort" right now. More work to do if we really want to be EARNEST
		Reference<?> mainRef = mainRef(target);
		if (mainRef.equals(target)) {
			if (settings.experimental().orphanDocumentMode() == MongoDriverSettings.OrphanDocumentMode.HASTY) {
				LOGGER.debug("Skipping deletePartsUnder({}) in {} mode", target, MongoDriverSettings.OrphanDocumentMode.HASTY);
			} else {
				String prefix;
				if (mainRef.path().isEmpty()) {
					prefix = "|";
				} else {
					prefix = docBsonPath(mainRef, rootRef) + "|";
				}

				// Every doc whose ID starts with the prefix and has at least one more character
				Bson filter = regex("_id", "^" + Pattern.quote(prefix) + ".");

				DeleteResult result = collection.deleteMany(filter);
				LOGGER.debug("deletePartsUnder({}) result: {} filter: {}", mainRef, result, filter);
			}
		} else {
			// TODO!
			assert settings.experimental().orphanDocumentMode() == MongoDriverSettings.OrphanDocumentMode.HASTY;
			LOGGER.debug("Skipping deletePartsUnder({}) because mainRef is different: {}", target, mainRef);
		}
	}

	/**
	 * @param value is mutated to stub-out the parts written to the database
	 */
	private <T> void upsertAndRemoveSubParts(Reference<T> target, BsonDocument value) {
		List<BsonDocument> allParts = bsonSurgeon.scatter(target, value);
		// NOTE: `value` has now been mutated so the parts have been stubbed out

		List<BsonDocument> subParts = allParts.subList(0, allParts.size() - 1);

		LOGGER.debug("Document has {} sub-parts", subParts.size());
		for (BsonDocument part: subParts) {
			BsonDocument filter = new BsonDocument("_id", part.get("_id"));
			LOGGER.debug("Pre-delete sub-part: filter={}", filter);
			collection.deleteOne(filter);
			LOGGER.debug("Insert sub-part: filter={} replacement={}", filter, part);
			InsertOneResult result = collection.insertOne(part); // we _must_ get the precise full document for sub-parts in the event stream, or we can't form the whole
			LOGGER.debug("| Insert result: {}", result);
		}

	}

	private void logNonexistentField(String dottedName, InvalidTypeException e) {
		LOGGER.trace("Nonexistent field {}",  dottedName, e);
		if (LOGGER.isWarnEnabled() && ALREADY_WARNED.add(dottedName)) {
			LOGGER.warn("Ignoring updates of nonexistent field {}", dottedName);
		}
	}

	@Override
	public String toString() {
		return description;
	}

	private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());
	private static final BsonDocument ROOT_DOCUMENT_FILTER = new BsonDocument("_id", ROOT_DOCUMENT_ID);
	private static final EnumSet<OperationType> OPERATIONS_TO_INCLUDE_IN_GATHER = EnumSet.of(INSERT);
	private static final Logger LOGGER = LoggerFactory.getLogger(PandoFormatDriver.class);
}
