package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
import works.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.exceptions.FormatMisconfigurationException;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.drivers.mongo.internal.TransactionalUpdateOperation.Outcome;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NotYetImplementedException;

import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.DELETE;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.bson.BsonBoolean.TRUE;
import static works.bosk.Path.parseParameterized;
import static works.bosk.drivers.mongo.internal.BsonFormatter.docBsonPath;
import static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf;
import static works.bosk.drivers.mongo.internal.TransactionalUpdateOperation.Outcome.ABORT;
import static works.bosk.drivers.mongo.internal.TransactionalUpdateOperation.Outcome.COMMIT;
import static works.bosk.util.Classes.enumerableByIdentifier;

/**
 * Implements {@link PandoFormat}.
 */
final class PandoFormatDriver<R extends StateTreeNode> extends AbstractFormatDriver<R> {
	private final String description;
	private final PandoFormat format;
	private final MongoDriverSettings settings;
	private final BsonSurgeon bsonSurgeon;
	private final Demultiplexer demultiplexer = new Demultiplexer();
	private final List<Reference<? extends EnumerableByIdentifier<?>>> graftPoints;

	private static final BsonString ROOT_DOCUMENT_ID = new BsonString("|");


	PandoFormatDriver(
		BoskInfo<R> boskInfo,
		SessionCollection collection,
		MongoDriverSettings driverSettings,
		PandoFormat format,
		BsonSerializer bsonSerializer,
		Duration flushTimeout,
		BoskDriver downstream
	) {
		super(
			boskInfo.rootReference(),
			boskInfo.context(),
			new Formatter(boskInfo, bsonSerializer),
			collection,
			downstream,
			flushTimeout
		);
		this.description = getClass().getSimpleName() + ": " + driverSettings;
		this.settings = driverSettings;
		this.format = format;
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
	public <T> UpdateOperation submitReplacement(Reference<T> target, T newValue) {
		return transactional(_ -> doReplacement(target, newValue));
	}

	@Override
	public <T> UpdateOperation submitConditionalCreation(Reference<T> target, T newValue) {
		return transactional(_ -> {
			if (target.isRoot()) {
				// Root node always already exists
				return ABORT;
			}
			Reference<?> targetDocRef = targetDocumentRef(target);
			if (target.equals(targetDocRef)) {
				// The target is itself a graft-point entry. Its own document may be
				// an orphan left behind by a HASTY deletion, so membership comes
				// from the container's stub, and the whole ancestor chain must
				// exist before we can create it.
				Reference<?> containerRef = targetDocumentRef(target.enclosingReference(Object.class));
				if (!containerExists(containerRef)) {
					LOGGER.debug("Parent does not exist: {}", target);
					return ABORT;
				}
				if (hasStub(target, containerRef)) {
					LOGGER.debug("Already exists: {}", target);
					return ABORT;
				}
				return doReplacement(target, newValue);
			} else {
				BsonDocument filter = documentFilter(targetDocRef)
					.append(dottedFieldNameOf(target, targetDocRef), new BsonDocument("$exists", TRUE));
				if (documentExists(filter)) {
					LOGGER.debug("Already exists: {}", filter);
					return ABORT;
				}
				return doReplacement(target, newValue);
			}
		});
	}

	@Override
	public <T> UpdateOperation submitDeletion(Reference<T> target) {
		return transactional(_ -> doDelete(target));
	}

	@Override
	public <T> UpdateOperation submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		return transactional(_ -> {
			if (preconditionFailed(precondition, requiredValue)) {
				return ABORT;
			}
			return doReplacement(target, newValue);
		});
	}

	@Override
	public <T> UpdateOperation submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		return transactional(_ -> {
			if (preconditionFailed(precondition, requiredValue)) {
				return ABORT;
			}
			return doDelete(target);
		});
	}

	private static TransactionalUpdateOperation transactional(TransactionalUpdateOperation operation) {
		return operation;
	}

	@Override
	BsonStateAndMetadata readBsonStateAndMetadata() throws InvalidCollectionContentsException {
		BsonStateAndMetadata bsm = null;
		List<BsonDocument> partsBuffer = new ArrayList<>();
		Map<BsonString, RevisionTuple> revisions = new HashMap<>();
		try (DocCursor cursor = collection
			.find(regex("_id", "^[|]"))
			.sort(new BsonDocument("_id", new BsonInt32(-1))) // Root doc last
			.cursor()
		) {
			while (cursor.hasNext()) {
				BsonDocument lastPart = cursor.next();
				BsonString id = lastPart.getString("_id");
				RevisionTuple revision = revisionOf(lastPart);
				revisions.put(id, revision);
				partsBuffer.add(lastPart);
				// The root document sorts last by _id.
				if (ROOT_DOCUMENT_ID.equals(id)) {
					// The lastPart is the root document

					// Pull what we need from the parts before gather() mutates them
					BsonDocument state = gather(partsBuffer); // mutates partsBuffer!

					bsm = new BsonStateAndMetadata(
						state,
						formatter.epochOf(lastPart),
						Map.copyOf(revisions)
					);

					// For historical reasons, this code is written as though there may be multiple independent
					// state trees. This style is still valid even though there's now only one state tree,
					// but the structure is still valid and interesting. But if this becomes a burden to maintain,
					// it could definitely be simplified; for example, there's no reason to do partsBuffer.clear().

					partsBuffer.clear();
				}
			}
		}

		if (!partsBuffer.isEmpty()) {
			throw new IllegalStateException("Found parts without a root document: "
				+ partsBuffer.stream().map(part -> part.get("_id")).toList());
		}

		if (bsm == null) {
			throw new NotYetImplementedException("Nothing there!");
		}
		return bsm;
	}

	private RevisionTuple revisionOf(BsonDocument document) throws InvalidCollectionContentsException {
		try {
			return RevisionTuple.from(document);
		} catch (BsonInvalidOperationException e) {
			throw new InvalidCollectionContentsException(format,
				"State document has malformed revision fields: " + document.getString("_id"), e);
		}
	}

	/**
	 * For efficiency, this modifies <code>partsList</code> in-place.
	 */
	private BsonDocument gather(List<BsonDocument> allParts) {
		return bsonSurgeon.gather(allParts);
	}

	private <T> List<BsonDocument> scatter(Reference<T> target, BsonDocument value) {
		return bsonSurgeon.scatter(target, value);
	}

	@Override
	public void writeAllState(StateAndMetadata<R> priorContents) {
		BsonString epoch = priorContents.epoch().orElseGet(() -> new BsonString(UUID.randomUUID().toString()));
		replaceFlushLock(Optional.of(epoch), priorContents.documentRevisions());
		BsonValue initialState = formatter.object2bsonValue(priorContents.state(), rootRef.targetType());
		RevisionTuple rootRevision = RevisionTuple.nextAfter(
			priorContents.documentRevisions().get(ROOT_DOCUMENT_ID));
		Map<BsonString, RevisionTuple> written = new HashMap<>();

		LOGGER.debug("** Initial upsert");
		writeAllState(initialState, epoch, rootRevision, written);

		// When writing the state, whether for initialState() or for refurbish(),
		// the local state will be up to date with no need to process a change stream event.
		finishedRevisions(written);
	}

	private void writeAllState(
		BsonValue initialState,
		BsonString epoch,
		RevisionTuple newRevision,
		Map<BsonString, RevisionTuple> writtenRevisions
	) {
		// The diagnostic attributes come from the current thread.
		// Note: the manifest is written by MainDriver, so the caller must have started
		// a transaction that makes these state document writes and the manifest write atomic.
		if (initialState instanceof BsonDocument) {
			upsertAndRemoveSubParts(rootRef, initialState.asDocument(), writtenRevisions); // Mutates initialState!
		}
		BsonDocument update = new BsonDocument("$set", initialDocument(initialState, epoch, newRevision, ROOT_DOCUMENT_ID));
		BsonDocument filter = rootDocumentsFilter();
		filter.put("_id", ROOT_DOCUMENT_ID);
		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result = collection.updateOne(filter, update, options);
		LOGGER.debug("| Result: {}", result);
		writtenRevisions.put(ROOT_DOCUMENT_ID, newRevision);
	}

	/**
	 * We're required to cope with anything we might ourselves do during initialization
	 * (writing the state documents in {@link #writeAllState}, and the manifest that
	 * {@link MainDriver} writes).
	 */
	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
		fieldTracker.processEvent(event);
		assert event.getDocumentKey() != null;
		BsonValue bsonDocumentID = event.getDocumentKey().get("_id");
		if (isManifestID(bsonDocumentID)) {
			/* We're required to cope with anything we might ourselves do during initialization,
			 * but outside that, we want to be as strict as we can
			 * so incompatible database changes don't go unnoticed.
			 */
			validateManifestEvent(event, Manifest.forPando(format));
			return;
		}
		if (!(bsonDocumentID instanceof BsonString s) || !(s.getValue().contains("|"))) {
			LOGGER.debug("Ignoring event for unrecognized document key: {} type {}", event.getDocumentKey(), bsonDocumentID.getClass());
			return;
		}

		// This is an event we care about
		routeEvent(event);
	}

	private void routeEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		if (event.getTxnNumber() == null) {
			LOGGER.debug("Processing standalone event {} on {}", event.getOperationType(), event.getDocumentKey());
			processTree(singletonList(event));
		} else {
			demultiplexer.add(event);
			if (isFinalEventOfTree(event)) {
				LOGGER.debug("Processing final event {} on {}", event.getOperationType(), event.getDocumentKey());
				processTree(demultiplexer.pop(event));
			} else {
				LOGGER.debug("Queueing event {} on {}", event.getOperationType(), event.getDocumentKey());
			}
		}
	}

	/**
	 * The final event of a transaction is the topmost document's revision bump.
	 * <p>
	 * For an {@code UPDATE}, that means the update includes the revision field.
	 * Only the topmost write bumps a revision, and the topmost document always
	 * already exists, so a normal transaction's final event is an {@code UPDATE}.
	 * <p>
	 * The exception is root initialization (and refurbish), which may insert the
	 * root document. Sub-document {@code INSERT}s also carry revisions, because
	 * every document has them, so they must not be mistaken for the final event.
	 * That is why an {@code INSERT} or {@code REPLACE} is final only for the root.
	 */
	private boolean isFinalEventOfTree(ChangeStreamDocument<BsonDocument> event) {
		return switch (event.getOperationType()) {
			case UPDATE -> updateEventHasField(event, DocumentFields.revision);
			case INSERT, REPLACE, DELETE -> isRootDocumentEvent(event);
			default -> false;
		};
	}

	private static boolean isRootDocumentEvent(ChangeStreamDocument<BsonDocument> event) {
		if (event.getDocumentKey() == null) {
			return false;
		}
		return ROOT_DOCUMENT_ID.equals(documentIdOf(event));
	}

	private void processTree(List<ChangeStreamDocument<BsonDocument>> treeEvents) throws UnprocessableEventException {
		if (treeEvents.isEmpty()) {
			return;
		}
		// In the per-document revision design, the final event is always the
		// topmost event: the write that changed the shallowest document's state
		// also bumped its revision. (Initialization is the same: the root write
		// carries both the state and the revision.)
		ChangeStreamDocument<BsonDocument> topmostEvent = treeEvents.getLast();
		List<ChangeStreamDocument<BsonDocument>> priorEvents = treeEvents.subList(0, treeEvents.size() - 1);
		BsonString docId = documentIdOf(topmostEvent);
		MapValue<String> diagnosticAttributes = diagnosticAttributesFor(docId);

		try (
			var _ = context.withOnly(diagnosticAttributes)
		) {
			switch (topmostEvent.getOperationType()) {
				case INSERT, REPLACE: {
					BsonDocument fullDocument = topmostEvent.getFullDocument();
					if (fullDocument == null) {
						throw new UnprocessableEventException("Missing fullDocument on final event", topmostEvent.getOperationType());
					}
					Optional<BsonString> epoch = formatter.epochOf(fullDocument);
					RevisionTuple revision = RevisionTuple.from(fullDocument);
					if (shouldSkip(epoch, docId, revision)) {
						LOGGER.debug("Skipping revision {} for {}", revision, docId);
						return;
					}

					LOGGER.debug("Topmost event is {}", topmostEvent.getOperationType());
					propagateDownstream(topmostEvent, priorEvents);

					if (!flushLock.get().epochMatches(epoch)) {
						// Adopt the new epoch so future flushes don't needlessly reconnect
						replaceFlushLock(epoch, Map.of());
					}
					recordRevisions(priorEvents);
					finishedRevision(docId, revision);
				} break;
				case UPDATE: {
					RevisionTuple revision = revisionFromUpdateEvent(docId, topmostEvent);
					if (shouldSkip(flushLock.get().epoch(), docId, revision)) {
						LOGGER.debug("Skipping revision {} for {}", revision, docId);
						return;
					}

					LOGGER.debug("Topmost event is final event");
					propagateDownstream(topmostEvent, priorEvents);
					recordRevisions(priorEvents);
					finishedRevision(docId, revision);
				} break;
				case DELETE: {
					// No other events in the transaction matter if the root document is gone.
					// This can happen during a refurbish, so we don't want to throw UnprocessableEventException
					// even though it's highly disruptive.
					// We deliberately don't clear the applied revisions: the re-created
					// document's revision and the epoch mechanism together ensure that
					// flushes still wait for the re-initialized collection.
					LOGGER.debug("Root document has been deleted");
				} break;
				default: {
					throw new UnprocessableEventException("Cannot process event", topmostEvent.getOperationType());
				}
			}
		}
	}

	/**
	 * @return the revision for the document whose revision was bumped by
	 * {@code event}, combining the event's new revision number with the
	 * document's current incarnation (a plain revision bump does not change the
	 * incarnation)
	 */
	private RevisionTuple revisionFromUpdateEvent(BsonString docId, ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription == null) {
			throw new UnprocessableEventException("Missing update description on final event", event.getOperationType());
		}
		BsonDocument updatedFields = updateDescription.getUpdatedFields();
		BsonInt64 revision = updatedFields.getInt64(DocumentFields.revision.name(), null);
		if (revision == null) {
			throw new UnprocessableEventException("Final event does not carry a revision", event.getOperationType());
		}
		BsonString incarnation = updatedFields.getString(DocumentFields.incarnation.name(), null);
		String incarnationValue = incarnation == null
			? requireNonNull(flushLock.get().currentIncarnation(docId), "Current incarnation")
			: incarnation.getValue();
		return new RevisionTuple(incarnationValue, revision.longValue());
	}

	/**
	 * Records the revisions carried by documents written earlier in the same
	 * transaction, so future flushes can wait for them even though only the
	 * topmost document's revision advances on ordinary updates.
	 */
	private void recordRevisions(List<ChangeStreamDocument<BsonDocument>> priorEvents) {
		for (ChangeStreamDocument<BsonDocument> event: priorEvents) {
			switch (event.getOperationType()) {
				case INSERT, REPLACE -> {
					BsonDocument fullDocument = event.getFullDocument();
					if (fullDocument != null) {
						finishedRevision(documentIdOf(event), RevisionTuple.from(fullDocument));
					}
				}
				default -> { /* Other events do not carry a revision number */ }
			}
		}
	}

	private void propagateDownstream(ChangeStreamDocument<BsonDocument> topmostEvent, List<ChangeStreamDocument<BsonDocument>> priorEvents) throws UnprocessableEventException {
		switch (topmostEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				BsonDocument fullDocument = topmostEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on topmost event", topmostEvent.getOperationType());
				}

				BsonDocument state = fullDocument.getDocument(DocumentFields.state.name(), null);
				if (state == null) {
					throw new UnprocessableEventException("Missing state field", topmostEvent.getOperationType());
				}

				Reference<?> topmostRef = documentIdToRef(
					documentIdOf(topmostEvent).getValue(), topmostEvent);
				BsonDocument bsonState;
				List<BsonDocument> parts = subpartDocuments(priorEvents);
				if (parts.isEmpty()) {
					LOGGER.debug("No prior events");
					bsonState = state;
				} else {
					LOGGER.debug("{} prior events", parts.size());
					parts.add(fullDocument);
					bsonState = gather(parts); // Mutates parts
				}

				LOGGER.debug("| Replace downstream {}", topmostRef);
				submitReplacementDownstream(topmostRef, bsonState);
			} break;
			case UPDATE: {
				Reference<?> topmostRef = documentIdToRef(documentIdOf(topmostEvent).getValue(), topmostEvent);
				UpdateDescription updateDescription = topmostEvent.getUpdateDescription();
				if (updateDescription != null) {
					replaceUpdatedFields(topmostRef, updateDescription.getUpdatedFields(), subpartDocuments(priorEvents), topmostEvent.getOperationType());
					deleteRemovedFields(topmostRef, updateDescription.getRemovedFields(), topmostEvent.getOperationType());
				}
			} break;
			case DELETE: {
				// No other events in the transaction matter if the topmost document is deleted
				Reference<?> deletedDocRef = targetDocumentRef(documentIdToRef(documentIdOf(topmostEvent).getValue(), topmostEvent));
				LOGGER.debug("| Delete downstream {}", deletedDocRef);
				downstream.submitDeletion(deletedDocRef);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", topmostEvent.getOperationType());
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
	private <T> void submitReplacementDownstream(Reference<T> topmostRef, BsonDocument bsonState) {
		T newValue = formatter.document2object(bsonState, topmostRef);
		downstream.submitReplacement(topmostRef, newValue);
	}

	private Reference<?> documentIdToRef(String documentId, ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		// referenceTo does everything we need already. Build a fake dotted field name and use that
		String dottedName = "state" + documentId
			.replace('|', '.');
		try {
			return BsonFormatter.referenceTo(dottedName, rootRef);
		} catch (InvalidTypeException e) {
			throw new UnprocessableEventException("Invalid path from document ID: \"" + documentId + "\"", e, event.getOperationType());
		}
	}

	private static boolean updateEventHasField(ChangeStreamDocument<BsonDocument> event, DocumentFields field) {
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
	 * A note on <em>pre-delete</em> operations:
	 * <p>
	 * A replacement that has sub-parts writes those sub-parts as separate
	 * documents, then sets the target field to a stub in the topmost document.
	 * The topmost document's update carries the revision bump that makes the
	 * transaction's final event identifiable.
	 * <p>
	 * Before setting the field, we pre-delete it: we unset it.
	 * This ensures the field changes even when the new value equals
	 * the old one, so the final event's {@code updatedFields} includes it and we
	 * can gather the sub-parts into the value we send downstream. Without this,
	 * an update whose value happened to be unchanged could leave us unable to
	 * tell which state field changed.
	 * <p>
	 * <em>Implementation note</em>: While {@link SequoiaFormatDriver} has calls like <code>doUpdate</code>,
	 * Pando uses <code>doReplacement</code> and <code>doDelete</code>.
	 * The reason is that Sequoia, being a single-document format, can implement all its preconditions
	 * (be they conditional updates, or existence of containing objects, etc.) as query filters,
	 * and so can do every update (replacements and deletions) as a single document update operation.
	 * In contrast, Pando implements its preconditions using read operations and actual if statements in the code,
	 * and so the actual replacement/deletion is unconditional, because it won't be executed if the precondition fails.
	 * <p>
	 * TODO: We could organize the Sequoia code in a similar manner if doReplacement and doDelete
	 *  accepted filter arguments (which Pando simply wouldn't use).
	 *  It's complicated by the fact that the zero-match case in Sequoia is highly ambiguous.
	 */
	private <T> Outcome doReplacement(Reference<T> target, T newValue) {
		LOGGER.debug("doReplacement({})", target);
		Reference<?> targetDocRef = targetDocumentRef(target);
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		boolean hasSubParts;
		if (value instanceof BsonDocument b) {
			deletePartsUnder(target);
			hasSubParts = upsertAndRemoveSubParts(target, b, null);
			// Note that value will now have the sub-parts removed
		} else {
			hasSubParts = false;
		}
		if (rootRef.equals(targetDocRef)) {
			LOGGER.debug("| Root ref is topmost");
			if (hasSubParts) {
				preDeleteField(target, rootRef, documentFilter(rootRef));
			} else {
				LOGGER.debug("| Skipping root pre-delete for {}: no sub-parts", target);
			}
			LOGGER.debug("| Update root document");
			boolean applied = doUpdate(replacementDoc(target, value, rootRef), standardPreconditions(target, rootRef, documentFilter(rootRef)));
			if (applied) {
				return COMMIT;
			} else {
				LOGGER.debug("| Replacement had no effect; aborting transaction");
				return ABORT;
			}
		} else if (target.equals(targetDocRef)) {
			return replaceGraftPointEntry(target, value, hasSubParts);
		} else {
			// Update part of the topmost document (which must already exist)
			BsonDocument filter = documentFilter(targetDocRef);
			if (hasSubParts) {
				preDeleteField(target, targetDocRef, filter);
			} else {
				LOGGER.debug("| Skipping pre-delete for {}: no sub-parts", target);
			}
			// This is the topmost write, so it carries the revision bump and the
			// diagnostics. No other document is written.
			boolean applied = doUpdate(replacementDoc(target, value, targetDocRef), standardPreconditions(target, targetDocRef, filter));

			if (applied) {
				return COMMIT;
			} else {
				LOGGER.debug("| Replacement had no effect; aborting transaction");
				return ABORT;
			}
		}
	}

	/**
	 * Replaces a whole state document, which is an entry in a graft-point container.
	 * <p>
	 * If the container does not exist, the replacement is ignored and the
	 * transaction is aborted.
	 * <p>
	 * Otherwise, if the container has a stub for the entry, the entry already
	 * exists, and the replacement is an update of the entry's own document: it
	 * keeps the document's incarnation, bumps its revision, and leaves the
	 * container untouched. The entry's document is then the topmost document of
	 * the transaction.
	 * <p>
	 * Otherwise, the entry is being created. Its document is written with a fresh
	 * lifetime, and the container's stub is added as the transaction's topmost
	 * write. That applies also when a deleted entry's document is still present as
	 * an orphan, so the presence of the document alone does not mean the entry
	 * exists.
	 */
	private <T> Outcome replaceGraftPointEntry(Reference<T> target, BsonValue value, boolean hasSubParts) {
		Reference<?> containerRef = targetDocumentRef(target.enclosingReference(Object.class));
		if (!containerExists(containerRef)) {
			LOGGER.debug("Parent does not exist; ignoring replacement of {}", target);
			return ABORT;
		}
		if (hasStub(target, containerRef)) {
			return updateGraftPointEntry(target, value, hasSubParts);
		} else {
			return createGraftPointEntryAndStub(target, containerRef, value);
		}
	}

	/**
	 * A whole graft-point entry exists iff its container has a stub for it and
	 * that container itself exists. The entry's own document may be an orphan
	 * left behind by a HASTY deletion, so its presence alone does not mean the
	 * entry exists, and a whole deleted subtree can remain on disk.
	 */
	private boolean entryExists(Reference<?> entryRef) {
		Reference<?> containerRef = targetDocumentRef(entryRef.enclosingReference(Object.class));
		if (!hasStub(entryRef, containerRef)) {
			return false;
		} else if (containerRef.isRoot()) {
			return true;
		} else {
			return entryExists(containerRef);
		}
	}

	/**
	 * @return true if the entry's container exists, so the entry can be created
	 * or updated
	 */
	private boolean containerExists(Reference<?> containerRef) {
		return containerRef.isRoot() || entryExists(containerRef);
	}

	private boolean hasStub(Reference<?> entryRef, Reference<?> containerRef) {
		String stubKey = dottedFieldNameOf(entryRef, containerRef);
		BsonDocument stubFilter = documentFilter(containerRef)
			.append(stubKey, new BsonDocument("$exists", TRUE));
		return documentExists(stubFilter);
	}

	private <T> Outcome updateGraftPointEntry(Reference<T> target, BsonValue value, boolean hasSubParts) {
		LOGGER.debug("| Replacing existing document {}", target);
		if (hasSubParts) {
			preDeleteField(target, target, documentFilter(target));
		}
		BsonDocument update = replacementDoc(target, value, target);
		// The container has a stub for this entry, so its document exists:
		// creation and deletion write the two atomically.
		if (doUpdate(update, documentFilter(target))) {
			return COMMIT;
		} else {
			LOGGER.debug("| Replacement had no effect; aborting transaction");
			return ABORT;
		}
	}

	private <T> Outcome createGraftPointEntryAndStub(Reference<T> target, Reference<?> containerRef, BsonValue value) {
		LOGGER.debug("| Creating document {}", target);
		BsonDocument document = documentFilter(target).clone()
			.append(DocumentFields.state.name(), value)
			.append(DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(context.getAttributes()));
		RevisionTuple.newLifetime().putInto(document);
		doReplace(documentFilter(target), document);

		// The container's stub is the transaction's topmost write.
		BsonDocument containerUpdate = replacementDoc(target, TRUE, containerRef);
		if (doUpdate(containerUpdate, standardPreconditions(target, containerRef, documentFilter(containerRef)))) {
			return COMMIT;
		} else {
			LOGGER.debug("| Container write had no effect; aborting transaction");
			return ABORT;
		}
	}

	private <T> Outcome doDelete(Reference<T> target) {
		deletePartsUnder(target);
		Reference<?> targetDocRef = targetDocumentRef(target);
		Reference<?> topmostRef = targetDocRef;
		if (targetDocRef.equals(target)) {
			// Delete the whole document
			if (settings.experimental().orphanDocumentMode() == OrphanDocumentMode.HASTY) {
				LOGGER.debug("Skipping deleting document({}) in {} mode", target, OrphanDocumentMode.HASTY);
			} else {
				throw new NotYetImplementedException("Earnest mode not yet implemented");
			}

			assert !targetDocRef.path().isEmpty(): "Can't delete the root reference";
			// Move up to the parent document to delete the "true" stub
			topmostRef = targetDocumentRef(targetDocRef.enclosingReference(Object.class));
			LOGGER.debug("| Move up to enclosing topmost reference {}", topmostRef);
		}

		// The deletion is the topmost write: it unsets the field and bumps the
		// topmost document's revision. No other document is written.
		if (doUpdate(deletionDoc(target, topmostRef), standardPreconditions(target, topmostRef, documentFilter(topmostRef)))) {
			return COMMIT;
		} else {
			LOGGER.debug("| Deletion had no effect; aborting transaction");
			return ABORT;
		}
	}

	private boolean preconditionFailed(Reference<Identifier> precondition, Identifier requiredValue) {
		Reference<?> preconditionDocRef = targetDocumentRef(precondition);
		if (!preconditionDocRef.isRoot() && !entryExists(preconditionDocRef)) {
			LOGGER.debug("Precondition fail: {} does not exist", preconditionDocRef);
			return true;
		}
		BsonDocument filter = documentFilter(preconditionDocRef)
			.append(dottedFieldNameOf(precondition, preconditionDocRef), new BsonString(requiredValue.toString()));
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
	private Reference<?> targetDocumentRef(Reference<?> target) {
		if (target.path().isEmpty()) {
			return rootRef;
		}

		// The target document reference is the "deepest" one that matches the target reference.
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
						throw new AssertionError("Unexpected exception forming targetDocumentRef from container " + candidateContainer + " and target " + target);
					}
				}
			}
		}
		return rootRef;
	}

	@Override
	public BsonDocument rootDocumentsFilter() {
		return new BsonDocument("_id", ROOT_DOCUMENT_ID);
	}

	@Override
	protected FlushRevisions readRevisionsToFlush() throws FlushFailureException, InterruptedException {
		LOGGER.debug("readRevisionsToFlush");
		try (DocCursor cursor = collection
			.findLatest(regex("_id", "^[|]"))
			.projection(fields(include(
				"_id",
				DocumentFields.epoch.name(),
				DocumentFields.incarnation.name(),
				DocumentFields.revision.name())))
			.sort(new BsonDocument("_id", new BsonInt32(-1))) // Bottom-up: root last
			.cursor()
		) {
			List<FlushLock.Target> bottomUp = new ArrayList<>();
			Optional<BsonString> epoch = Optional.empty();
			boolean foundRoot = false;
			while (cursor.hasNext()) {
				BsonDocument document = cursor.next();
				BsonString id = document.getString("_id");
				bottomUp.add(new FlushLock.Target(id, RevisionTuple.from(document)));
				if (isRootDocumentId(id)) {
					epoch = formatter.epochOf(document);
					foundRoot = true;
				}
			}
			if (!foundRoot) {
				throw new RevisionFieldDisruptedException("No root documents found");
			}
			// Wait top-down: ancestors before descendants.
			Collections.reverse(bottomUp);
			return new FlushRevisions(epoch, bottomUp);
		} catch (MongoInterruptedException e) {
			Thread.interrupted();
			InterruptedException exception = new InterruptedException("Interrupted while reading revisions");
			exception.initCause(e);
			throw exception;
		} catch (MongoException e) {
			throw new FlushFailureException("Failed to read revisions", e);
		}
	}

	@Override
	protected BsonDocument documentFilter(Reference<?> docRef) {
		return new BsonDocument("_id", new BsonString(docBsonPath(docRef, rootRef)));
	}

	private <T> void preDeleteField(Reference<T> target, Reference<?> startingRef, BsonDocument filter) {
		String key = dottedFieldNameOf(target, startingRef);
		preDelete(target, startingRef, filter, key, new BsonDocument("$unset", new BsonDocument(key, BsonNull.VALUE)));
	}

	private <T> void preDelete(Reference<T> target, Reference<?> startingRef, BsonDocument filter, String key, BsonDocument update) {
		LOGGER.debug("| Pre-delete field {} in {}", key, startingRef);
		// Important: don't bump the revision field because that's how we identify the last event in a transaction
		doUpdate(update, standardPreconditions(target, startingRef, filter));
	}

	/**
	 * @return true if something changed
	 */
	@Override
	protected boolean doUpdate(BsonDocument updateDoc, BsonDocument filter) {
		LOGGER.debug("| Update: {}", updateDoc);
		LOGGER.debug("| Filter: {}", filter);
		UpdateResult result = collection.updateOne(filter, updateDoc);
		LOGGER.debug("| Update result: {}", result);
		requireAcknowledged(result, filter, updateDoc, "Update");
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
	}

	/**
	 * Replaces a whole document, creating it if it does not exist.
	 * <p>
	 * Unlike {@link #doUpdate}, which does nothing unless the document already
	 * exists, this creates the document when it is absent.
	 */
	private void doReplace(BsonDocument filter, BsonDocument replacement) {
		LOGGER.debug("| Replace: filter={} replacement={}", filter, replacement);
		UpdateResult result = collection.replaceOne(filter, replacement, new ReplaceOptions().upsert(true));
		LOGGER.debug("| Replace result: {}", result);
		requireAcknowledged(result, filter, replacement, "Replacement");
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitReplacement submitReplacement}</code>
	 * for each updated field.
	 */
	private void replaceUpdatedFields(Reference<?> topmostRef, @Nullable BsonDocument updatedFields, List<BsonDocument> subParts, OperationType operationType) throws UnprocessableEventException {
		if (updatedFields != null) {
			boolean alreadyUsedSubparts = false;
			for (Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = BsonFormatter.referenceTo(dottedName, topmostRef);
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
						// Sub-part document IDs are absolute BSON paths, so the
						// synthetic part for the replaced value must be absolute too.
						String partID = docBsonPath(ref, rootRef);
						BsonDocument partDocument = new BsonDocument()
							.append("_id", new BsonString(partID))
							.append("state", replacementValue);
						ArrayList<BsonDocument> parts = new ArrayList<>(subParts.size() + 1);
						parts.addAll(subParts);
						parts.add(partDocument);

						replacementValue = gather(parts);
					} else if (subParts.isEmpty()) {
						LOGGER.debug("Replacement value is scalar: {}", replacementValue);
					} else if (TRUE.equals(replacementValue)) {
						LOGGER.debug("Replacement value is stub; gather {} subparts", subParts.size());
						replacementValue = gather(subParts);
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

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(Reference<?> topmostRef, @Nullable List<String> removedFields, OperationType operationType) throws UnprocessableEventException {
		if (removedFields != null) {
			for (String dottedName : removedFields) {
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = BsonFormatter.referenceTo(dottedName, topmostRef);
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
		Reference<?> targetDocRef = targetDocumentRef(target);
		if (targetDocRef.equals(target)) {
			if (settings.experimental().orphanDocumentMode() == OrphanDocumentMode.HASTY) {
				LOGGER.debug("Skipping deletePartsUnder({}) in {} mode", target, OrphanDocumentMode.HASTY);
			} else {
				String prefix;
				if (targetDocRef.path().isEmpty()) {
					prefix = "|";
				} else {
					prefix = docBsonPath(targetDocRef, rootRef) + "|";
				}

				// Every doc whose ID starts with the prefix and has at least one more character
				Bson filter = regex("_id", "^" + Pattern.quote(prefix) + ".");

				DeleteResult result = collection.deleteMany(filter);
				LOGGER.debug("deletePartsUnder({}) result: {} filter: {}", targetDocRef, result, filter);
			}
		} else {
			// TODO!
			assert settings.experimental().orphanDocumentMode() == OrphanDocumentMode.HASTY;
			LOGGER.debug("Skipping deletePartsUnder({}) because target document is different: {}", target, targetDocRef);
		}
	}


	/**
	 * @param value is mutated to stub-out the parts written to the database
	 * @param writtenRevisions if non-null, receives the revision assigned to each
	 * sub-part written
	 */
	private <T> boolean upsertAndRemoveSubParts(
		Reference<T> target,
		BsonDocument value,
		@Nullable Map<BsonString, RevisionTuple> writtenRevisions
	) {
		List<BsonDocument> allParts = scatter(target, value);
		// NOTE: `value` has now been mutated so the parts have been stubbed out

		List<BsonDocument> subParts = allParts.subList(0, allParts.size() - 1);

		LOGGER.debug("Document has {} sub-parts", subParts.size());
		for (BsonDocument part: subParts) {
			// scatter() already prepended idPrefix to the _id; don't prepend it again
			BsonString id = part.getString("_id");
			BsonDocument filter = new BsonDocument("_id", id);
			RevisionTuple newRevision = RevisionTuple.newLifetime();
			newRevision.putInto(part);
			// We _must_ get the precise full document for sub-parts in the event
			// stream, or we can't form the whole. Both an INSERT (when the part is
			// absent) and a REPLACE (when it already exists) carry it, so one
			// upserting replace suffices; a delete followed by an insert would be
			// two writes per part.
			LOGGER.debug("Upsert sub-part: filter={} replacement={}", filter, part);
			UpdateResult result = collection.replaceOne(filter, part, new ReplaceOptions().upsert(true));
			LOGGER.debug("| Upsert result: {}", result);
			if (writtenRevisions != null) {
				writtenRevisions.put(id, newRevision);
			}
		}

		return !subParts.isEmpty();
	}

	@Override
	public void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata) {
		super.onHasBeenApplied(stateAndMetadata);
	}

	@Override
	public String toString() {
		return description;
	}

	// A created entry's document is written with replaceOne, which produces an
	// INSERT when the document is absent and a REPLACE when it is an orphan left
	// behind by a deletion. Both carry the full document we need to gather.
	private static final EnumSet<OperationType> OPERATIONS_TO_INCLUDE_IN_GATHER = EnumSet.of(INSERT, REPLACE);
	private static final Logger LOGGER = LoggerFactory.getLogger(PandoFormatDriver.class);
}
