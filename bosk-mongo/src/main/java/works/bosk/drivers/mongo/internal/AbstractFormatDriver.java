package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.UpdateResult;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.drivers.mongo.status.BsonComparator;
import works.bosk.drivers.mongo.status.MongoStatus;
import works.bosk.drivers.mongo.status.StateStatus;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;

import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;

abstract non-sealed class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final BoskContext context;
	final Formatter formatter;
	final TransactionalCollection collection;
	final BoskDriver downstream;
	final long flushTimeoutMS;

	final AtomicReference<FlushLock> flushLock = new AtomicReference<>(null);

	final DocumentFieldTracker fieldTracker = new DocumentFieldTracker();

	public AbstractFormatDriver(
		RootReference<R> rootRef,
		BoskContext context,
		Formatter formatter,
		TransactionalCollection collection,
		BoskDriver downstream,
		long flushTimeoutMS
	) {
		this.rootRef = rootRef;
		this.context = context;
		this.formatter = formatter;
		this.collection = collection;
		this.downstream = downstream;
		this.flushTimeoutMS = flushTimeoutMS;
	}

	@Override
	public MongoStatus readStatus() {
		try {
			BsonStateAndMetadata dbInfo = readBsonStateAndMetadata();
			BsonDocument dbState = dbInfo.state();
			var inMemoryState = formatter.object2bsonValue(rootRef.valueIfExists(), rootRef.targetType());
			var stateStatus = new StateStatus(
				dbInfo.revision().longValue(),
				formatter.bsonValueBinarySize(dbState),
				new BsonComparator().difference(inMemoryState, dbState)
			);
			return new MongoStatus(
				null,
				null, // MainDriver should fill this in
				stateStatus
			);
		} catch (InvalidCollectionContentsException e) {
			return new MongoStatus(
				e.toString(),
				null,
				null
			);
		}
	}

	@Override
	public StateAndMetadata<R> loadAllState() throws IOException, InvalidCollectionContentsException {
		BsonStateAndMetadata bsm = readBsonStateAndMetadata();
		if (bsm.state() == null) {
			throw new IOException("No existing state in document");
		}
		replaceFlushLock(bsm.revision());
		fieldTracker.process(bsm);

		R root = formatter.document2object(bsm.state(), rootRef);
		MapValue<String> diagnosticAttributes = bsm.diagnosticAttributes() == null
			? MapValue.empty() // It's not clear what missing attributes mean, but using null here would have the effect of leaving the old attributes in place, which seems flaky
			: formatter.decodeDiagnosticAttributes(bsm.diagnosticAttributes());

		return new StateAndMetadata<>(root, bsm.revision(), diagnosticAttributes);
	}

	@Override
	public void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata) {
		flushLock.get().finishedRevision(stateAndMetadata.revision());
	}

	/**
	 * Must be called before {@link FormatDriver#onHasBeenApplied} or any event processing.
	 */
	protected void replaceFlushLock(BsonInt64 revisionNumber) {
		flushLock.set(new FlushLock(revisionNumber.longValue(), flushTimeoutMS));
	}

	abstract BsonStateAndMetadata readBsonStateAndMetadata() throws InvalidCollectionContentsException;

	protected BsonDocument blankUpdateDoc() {
		return new BsonDocument()
			.append("$inc", new BsonDocument(DocumentFields.revision.name(), new BsonInt64(1)))
			.append("$set", new BsonDocument()
				.append(
					DocumentFields.diagnostics.name(),
					formatter.encodeDiagnostics(context.getAttributes())
				)
			);
	}

	protected void logNonexistentField(String dottedName, InvalidTypeException e) {
		LOGGER.trace("Nonexistent field {}", dottedName, e);
		if (LOGGER.isWarnEnabled() && ALREADY_WARNED.add(dottedName)) {
			LOGGER.warn("Ignoring updates of nonexistent field {}", dottedName);
		}
	}

	protected <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Set field {}: {}", key, value);
		BsonDocument result = blankUpdateDoc();
		result.compute("$set", (_, existing) -> {
			if (existing == null) {
				return new BsonDocument(key, value);
			} else {
				return existing.asDocument().append(key, value);
			}
		});
		return result;
	}

	protected <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Unset field {}", key);
		return blankUpdateDoc().append("$unset", new BsonDocument(key, BsonNull.VALUE));
	}

	protected boolean shouldSkip(BsonInt64 revision) {
		return flushLock.get().alreadySeen(revision);
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection},
	 * but outside that, we want to be as strict as possible
	 * so incompatible database changes don't go unnoticed.
	 */
	protected void validateManifestEvent(ChangeStreamDocument<BsonDocument> event, Manifest effectiveManifest) throws UnprocessableEventException {
		LOGGER.debug("onManifestEvent({})", event.getOperationType().name());
		if (event.getOperationType() == INSERT || event.getOperationType() == REPLACE) {
			BsonDocument manifestDoc = requireNonNull(event.getFullDocument());
			Manifest manifest;
			try {
				manifest = formatter.decodeManifest(manifestDoc);
			} catch (UnrecognizedFormatException e) {
				throw new UnprocessableEventException("Invalid manifest", e, event.getOperationType());
			}
			if (!manifest.equals(effectiveManifest)) {
				throw new UnprocessableEventException("Manifest indicates format has changed", event.getOperationType());
			}
		} else {
			// We always use INSERT/REPLACE to update the manifest;
			// anything else is unexpected.
			throw new UnprocessableEventException("Unexpected change to manifest document", event.getOperationType());
		}
		LOGGER.debug("Ignoring benign manifest change event");
	}

	protected void finishedRevision(BsonInt64 revision) {
		flushLock.get().finishedRevision(revision);
	}

	/**
	 * @return cursor giving the {@code _id} and {@code revision}
	 * for all root documents that have a revision field.
	 */
	protected MongoCursor<BsonDocument> revisionDocumentCursor() {
		return collection
			.findLatest(rootDocumentsFilter())
			.projection(fields(include("_id", DocumentFields.revision.name())))
			.cursor();
	}

	/**
	 * If there's any other waiting to be done besides the revision number waiting,
	 * this method must do that before returning.
	 * @return revision number found in the database
	 * @throws RevisionFieldDisruptedException if unexpected database contents make it impossible to determine the revision number
	 */
	abstract @NonNull BsonInt64 readRevisionNumberToFlush() throws FlushFailureException, InterruptedException;

	@Override
	public void flush() throws IOException, InterruptedException {
		var revision = readRevisionNumberToFlush();

		// Don't hold a database transaction while waiting for the flush lock
		// or flushing downstream.
		collection.commitTransactionIfAny();

		LOGGER.debug("Revisions to flush: {}", revision);
		flushLock.get().awaitRevision(revision);
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		flushLock.get().close();
	}

	protected void writeManifest(Manifest manifest) {
		BsonDocument doc = new BsonDocument("_id", requireNonNull(MANIFEST_ID));
		doc.putAll((BsonDocument) formatter.object2bsonValue(manifest, Manifest.class));
		BsonDocument filter = new BsonDocument("_id", MANIFEST_ID);
		LOGGER.debug("| Initial manifest: {}", doc);
		ReplaceOptions options = new ReplaceOptions().upsert(true);
		UpdateResult result = collection.replaceOne(filter, doc, options);
		LOGGER.debug("| Manifest result: {}", result);
	}

	protected BsonDocument initialDocument(BsonValue initialState, BsonInt64 revision, BsonString documentId) {
		BsonDocument fieldValues = new BsonDocument("_id", documentId);

		fieldValues.put(DocumentFields.path.name(), new BsonString("/"));
		fieldValues.put(DocumentFields.state.name(), initialState);
		fieldValues.put(DocumentFields.revision.name(), revision);
		fieldValues.put(DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(context.getAttributes()));

		return fieldValues;
	}

	protected boolean isManifestID(BsonValue documentId) {
		return MANIFEST_ID.equals(documentId);
	}

	/**
	 * Low-level version of {@link StateAndMetadata}.
	 */
	record BsonStateAndMetadata(
		BsonString _id,
		BsonInt64 revision,
		BsonDocument diagnosticAttributes,
		BsonDocument state
	){}

	private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFormatDriver.class);

}
