package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
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

import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf;
import static works.bosk.drivers.mongo.internal.DocumentFieldTracker.TrackedField.DIAGNOSTICS;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;

abstract non-sealed class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final BoskContext context;
	final Formatter formatter;
	final SessionCollection collection;
	final BoskDriver downstream;
	final Duration flushTimeout;

	final AtomicReference<FlushLock> flushLock = new AtomicReference<>(null);

	final DocumentFieldTracker fieldTracker = new DocumentFieldTracker();

	public AbstractFormatDriver(
		RootReference<R> rootRef,
		BoskContext context,
		Formatter formatter,
		SessionCollection collection,
		BoskDriver downstream,
		Duration flushTimeout
	) {
		this.rootRef = rootRef;
		this.context = context;
		this.formatter = formatter;
		this.collection = collection;
		this.downstream = downstream;
		this.flushTimeout = flushTimeout;
	}

	@Override
	public MongoStatus readStatus() {
		try {
			BsonStateAndMetadata dbInfo = readBsonStateAndMetadata();
			BsonDocument dbState = dbInfo.state();
			var inMemoryState = formatter.object2bsonValue(rootRef.valueIfExists(), rootRef.targetType());
			var stateStatus = new StateStatus(
				rootRevision(dbInfo).revision(),
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
		// The new FlushLock must not consider the loaded revisions "already seen":
		// MainDriver publishes this driver before applying the loaded state downstream,
		// and a flush that failed during a disconnect and is retrying could wake up on the
		// publish and, seeing the loaded revision already satisfied, report success while
		// the in-memory state is still stale. Seeding the lock one revision short makes
		// such flushes wait until MainDriver calls onHasBeenApplied, which advances the
		// lock once the loaded state has actually been applied downstream.
		replaceFlushLock(bsm.epoch(), revisionsBefore(bsm.documentRevisions()));

		R root = formatter.document2object(bsm.state(), rootRef);

		return new StateAndMetadata<>(root, bsm.epoch(), bsm.documentRevisions());
	}

	@Override
	public void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata) {
		flushLock.get().finishedAll(stateAndMetadata.documentRevisions());
	}

	/**
	 * @return a copy of {@code revisions} with each revision decremented by one,
	 * suitable for seeding a {@link FlushLock} before the loaded state has been
	 * applied downstream
	 */
	static Map<BsonString, RevisionTuple> revisionsBefore(Map<BsonString, RevisionTuple> revisions) {
		Map<BsonString, RevisionTuple> result = new HashMap<>(revisions.size());
		revisions.forEach((documentId, revision) ->
			result.put(documentId, new RevisionTuple(revision.incarnation(), revision.revision() - 1)));
		return result;
	}

	/**
	 * Must be called before {@link FormatDriver#onHasBeenApplied} or any event processing.
	 *
	 * @param applied the revision whose state has already been applied to the
	 * local Bosk state, for each document, keyed by document ID. A flush returns
	 * immediately for these and waits for anything newer. A load passes each
	 * revision one short, because the loaded state is not applied until
	 * {@link #onHasBeenApplied}.
	 */
	protected void replaceFlushLock(Optional<BsonString> epoch, Map<BsonString, RevisionTuple> applied) {
		flushLock.set(new FlushLock(epoch, applied, flushTimeout));
	}

	abstract BsonStateAndMetadata readBsonStateAndMetadata() throws InvalidCollectionContentsException;

	/**
	 * Reads every state document's revision number and incarnation, in bottom-up
	 * order (descendants before ancestors), for {@link #flush()} to wait on.
	 * <p>
	 * Descendants are read first so that, if a concurrent transaction removes a
	 * descendant and bumps an ancestor, the ancestor read happens after the
	 * removal and therefore observes the ancestor's new revision. Waiting then
	 * proceeds top-down, so the ancestor's transaction is applied before its
	 * descendants' revisions are interpreted.
	 */
	protected abstract FlushRevisions readRevisionsToFlush() throws FlushFailureException, InterruptedException;

	/**
	 * The change to the topmost document of a transaction: it bumps that
	 * document's revision and records the current diagnostic attributes.
	 * <p>
	 * The resulting update document is meant to be applied to the filter that
	 * selects the topmost document. Other writes in the same transaction must not
	 * bump a revision.
	 */
	protected BsonDocument revisionBump() {
		return new BsonDocument()
			.append("$inc", new BsonDocument(DocumentFields.revision.name(), new BsonInt64(1)))
			.append("$set", new BsonDocument()
				.append(
					DocumentFields.diagnostics.name(),
					formatter.encodeDiagnostics(context.getAttributes())
				)
			);
	}

	/**
	 * @return the diagnostic attributes last seen for {@code documentId}, or an
	 * empty set if none are known
	 */
	protected MapValue<String> diagnosticAttributesFor(BsonString documentId) {
		BsonDocument attributes = fieldTracker.getFieldAsDocument(
			documentId, DIAGNOSTICS);
		return attributes == null
			? MapValue.empty()
			: formatter.decodeDiagnosticAttributes(attributes);
	}

	protected static BsonString documentIdOf(ChangeStreamDocument<BsonDocument> event) {
		return event.getDocumentKey().getString("_id");
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
		BsonDocument result = revisionBump();
		result.getDocument("$set").append(key, value);
		return result;
	}

	protected <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Unset field {}", key);
		return revisionBump().append("$unset", new BsonDocument(key, BsonNull.VALUE));
	}

	/**
	 * An event from a different epoch indicates the collection has been reinitialized,
	 * so we must not skip it based on the previous initialization's revision numbers.
	 *
	 * @param eventEpoch the collection epoch at the time of the event; {@link Optional#empty()}
	 * for legacy databases, in which case only the revision is considered
	 * @param documentId the document whose revision is being compared
	 * @param revision the document's revision from the event
	 */
	protected boolean shouldSkip(Optional<BsonString> eventEpoch, BsonString documentId, RevisionTuple revision) {
		FlushLock lock = flushLock.get();
		return lock.epochMatches(eventEpoch) && lock.alreadySeen(documentId, revision);
	}

	/**
	 * We're required to cope with anything we might ourselves do during initialization
	 * (writing the state documents in {@link FormatDriver#writeAllState}, and the
	 * manifest that {@link MainDriver} writes), but outside that, we want to be as
	 * strict as possible so incompatible database changes don't go unnoticed.
	 */
	protected void validateManifestEvent(ChangeStreamDocument<BsonDocument> event, Manifest effectiveManifest) throws UnprocessableEventException, FormatChangedException {
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
				throw new FormatChangedException("Manifest indicates format has changed", event.getOperationType());
			}
		} else {
			// We always use INSERT/REPLACE to update the manifest;
			// anything else is unexpected.
			throw new UnprocessableEventException("Unexpected change to manifest document", event.getOperationType());
		}
		LOGGER.debug("Ignoring benign manifest change event");
	}

	/**
	 * Marks the given documents' revisions as applied, waking any flush waiting
	 * for them.
	 */
	protected void finishedRevisions(Map<BsonString, RevisionTuple> revisions) {
		flushLock.get().finishedAll(revisions);
	}

	/**
	 * Marks one document's revision as applied, waking any flush waiting for it.
	 */
	protected void finishedRevision(BsonString documentId, RevisionTuple revision) {
		flushLock.get().finished(documentId, revision);
	}

	/**
	 * @return true if {@code documentId} identifies the root document, as
	 * selected by {@link #rootDocumentsFilter()}
	 */
	protected boolean isRootDocumentId(BsonString documentId) {
		return documentId.equals(rootDocumentsFilter().get("_id"));
	}

	private RevisionTuple rootRevision(BsonStateAndMetadata bsm) {
		return requireNonNull(
			bsm.documentRevisions().get(rootDocumentsFilter().getString("_id")),
			"Root document revision");
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		// The revision read uses findLatest, which bypasses the session and reads
		// the latest committed values, so flush needs no session or transaction.
		collection.requireNoTransaction();
		FlushRevisions revisions = readRevisionsToFlush();
		FlushLock lock = flushLock.get();

		if (!lock.epochMatches(revisions.epoch())) {
			// The collection has been reinitialized since we loaded it.
			// We must not wait on our stale revision numbers; instead, throw
			// so the driver disconnects, reloads the new state, and retries.
			throw new EpochMismatchException("Collection epoch has changed from "
				+ lock.epoch() + " to " + revisions.epoch());
		}

		LOGGER.debug("Revisions to flush: {}", revisions.targets());
		lock.awaitAll(revisions.targets());
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		FlushLock lock = flushLock.get();
		if (lock != null) {
			// A driver that was created but never loaded its state has no FlushLock.
			lock.close();
		}
	}

	protected BsonDocument initialDocument(BsonValue initialState, BsonString epoch, RevisionTuple revision, BsonString documentId) {
		BsonDocument fieldValues = new BsonDocument("_id", documentId);

		fieldValues.put(DocumentFields.state.name(), initialState);
		fieldValues.put(DocumentFields.epoch.name(), epoch);
		revision.putInto(fieldValues);
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
		BsonDocument state,
		Optional<BsonString> epoch,
		Map<BsonString, RevisionTuple> documentRevisions
	){}

	/**
	 * The revisions {@link #flush()} should wait for, plus the collection epoch
	 * to validate them against.
	 *
	 * @param epoch the collection epoch, empty for a legacy database
	 * @param targets the documents to wait for, in top-down order
	 */
	record FlushRevisions(Optional<BsonString> epoch, List<FlushLock.Target> targets) {}

	private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFormatDriver.class);

}
