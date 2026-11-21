package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.ReadConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.UpdateResult;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.conversions.Bson;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonTimestamp;
import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.Identifier;
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

import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.bson.BsonBoolean.FALSE;
import static org.bson.BsonBoolean.TRUE;
import static works.bosk.ReferenceUtils.isTaggedUnionCase;
import static works.bosk.drivers.mongo.internal.AtomicUpdateOperation.Outcome.DONE;
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
	final Identifier epoch;

	final AtomicReference<FlushLock> flushLock = new AtomicReference<>(null);
	final FlushBarrier barrier;
	private final boolean flushBarrierEnabled;

	final DocumentFieldTracker fieldTracker = new DocumentFieldTracker();

	public AbstractFormatDriver(
		RootReference<R> rootRef,
		BoskContext context,
		Formatter formatter,
		SessionCollection collection,
		BoskDriver downstream,
		Duration flushTimeout,
		Identifier epoch,
		boolean flushBarrierEnabled
	) {
		this.rootRef = rootRef;
		this.context = context;
		this.formatter = formatter;
		this.collection = collection;
		this.downstream = downstream;
		this.flushTimeout = flushTimeout;
		this.epoch = epoch;
		this.flushBarrierEnabled = flushBarrierEnabled;
		this.barrier = new FlushBarrier();
	}

	@Override
	public Identifier epoch() {
		return epoch;
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
		replaceFlushLock(revisionsBefore(bsm.documentRevisions()));

		R root;
		try {
			root = formatter.document2object(bsm.state(), rootRef);
		} catch (BsonInvalidOperationException e) {
			// Only this exception means the data is bad; the others mean the code is bad.
			throw new InvalidCollectionContentsException(format(), "State document could not be decoded", e);
		}

		return new StateAndMetadata<>(root, bsm.documentRevisions());
	}

	@Override
	public void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata) {
		flushLock.get().finishedAll(stateAndMetadata.documentRevisions());
	}

	@Override
	public void markSubmitted(BsonTimestamp position) {
		barrier.markSubmitted(position);
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
	protected void replaceFlushLock(Map<BsonString, RevisionTuple> applied) {
		flushLock.set(new FlushLock(applied, flushTimeout));
	}

	abstract BsonStateAndMetadata readBsonStateAndMetadata() throws InvalidCollectionContentsException;

	/**
	 * @return a filter selecting the state document corresponding to {@code documentRef}
	 */
	protected abstract BsonDocument documentFilter(Reference<?> documentRef);

	/**
	 * Applies one database update.
	 *
	 * @return true if the update matched at least one document
	 */
	protected abstract boolean doUpdate(BsonDocument updateDoc, BsonDocument filter);

	/**
	 * Reads every state document's revision number and incarnation, in bottom-up
	 * order (descendants before ancestors), for {@link #flush()} to wait on.
	 * <p>
	 * Descendants are read first so that, if a concurrent transaction removes a
	 * descendant and bumps an ancestor, the ancestor read happens after the
	 * removal and therefore observes the ancestor's new revision. Waiting then
	 * proceeds top-down, so the ancestor's transaction is applied before its
	 * descendants' revisions are interpreted.
	 * <p>
	 * Each document's epoch is checked against this driver's, so a collection that
	 * has been reinitialized or refurbished since the driver loaded it fails the flush.
	 */
	protected abstract List<FlushLock.Target> readRevisionsToFlush() throws FlushFailureException, InterruptedException;

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

	/**
	 * Adds this driver's epoch to the given filter, so a write planned
	 * against a format that has since been refurbished away matches nothing
	 * rather than landing in a document from another format.
	 */
	protected BsonDocument withEpoch(BsonDocument filter) {
		BsonDocument result = filter.clone();
		result.put(DocumentFields.epoch.name(), new BsonString(epoch.toString()));
		return result;
	}

	/**
	 * Verifies that a loaded state document carries the epoch the manifest
	 * declares.
	 *
	 * @throws InvalidCollectionContentsException if it doesn't
	 */
	protected void validateEpoch(BsonDocument document) throws InvalidCollectionContentsException {
		BsonString expected = new BsonString(epoch.toString());
		BsonValue found = document.get(DocumentFields.epoch.name());
		if (!expected.equals(found)) {
			throw new InvalidCollectionContentsException(format(), "Epoch mismatch on state document "
				+ document.getString("_id").getValue() + ": expected " + expected.getValue()
				+ " from the manifest, found " + (found == null? "nothing": found.asString().getValue()));
		}
	}

	/**
	 * Verifies that a change event carries this driver's epoch. A document
	 * with a different one means the database has been refurbished, so the driver
	 * must reload it.
	 *
	 * @throws CollectionRewrittenException if the event's document has a different epoch
	 * @throws UnprocessableEventException if the event modifies the epoch, which no valid update does
	 */
	protected void validateEventEpoch(ChangeStreamDocument<BsonDocument> event) throws CollectionRewrittenException, UnprocessableEventException {
		BsonDocument fullDocument = event.getFullDocument();
		if (fullDocument != null) {
			BsonString expected = new BsonString(epoch.toString());
			BsonValue found = fullDocument.get(DocumentFields.epoch.name());
			if (!expected.equals(found)) {
				throw new CollectionRewrittenException("State document " + documentIdOf(event).getValue()
					+ " has epoch " + found + ", but this driver is using " + expected.getValue(),
					event.getOperationType());
			}
		}
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription != null && modifiesEpoch(updateDescription)) {
			throw new UnprocessableEventException("Update modifies the epoch of " + documentIdOf(event).getValue(),
				event.getOperationType());
		}
	}

	private static boolean modifiesEpoch(UpdateDescription updateDescription) {
		String field = DocumentFields.epoch.name();
		List<String> removedFields = updateDescription.getRemovedFields();
		return updateDescription.getUpdatedFields().containsKey(field)
			|| (removedFields != null && removedFields.contains(field));
	}

	/**
	 * Checks that the epoch has not changed since this driver was initialized.
	 * <p>
	 * {@link UpdateDriver} calls this when an update changes nothing, because a
	 * write that matched nothing may have missed because the collection was
	 * refurbished to another format rather than because the update had nothing to do.
	 *
	 * @throws FormatChangedDuringUpdateException if the format has changed
	 */
	@Override
	public void requireFormatUnchanged() throws FormatChangedDuringUpdateException {
		try (var cursor = collection
			.find(new BsonDocument("_id", MANIFEST_ID))
			.projection(fields(include(DocumentFields.epoch.name())))
			.limit(1)
			.cursor()
		) {
			if (!cursor.hasNext()) {
				throw new FormatChangedDuringUpdateException("Missing manifest document");
			}
			BsonValue value = cursor.next().get(DocumentFields.epoch.name());
			if (!(value instanceof BsonString manifestEpoch)) {
				throw new FormatChangedDuringUpdateException("Missing epoch in the manifest");
			}
			if (!manifestEpoch.getValue().equals(epoch.toString())) {
				throw new FormatChangedDuringUpdateException("The database epoch changed from " + epoch + " to " + manifestEpoch.getValue() + " while an update was in progress");
			}
		}
	}

	protected <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Set field {}", key);
		LOGGER.trace("| Set field {}: {}", key, value);
		BsonDocument result = revisionBump();
		result.getDocument("$set").append(key, value);
		return result;
	}

	protected <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
		assert target.isDeletable(): "Cannot delete a non-deletable reference: " + target;
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Unset field {}", key);
		return revisionBump().append("$unset", new BsonDocument(key, BsonNull.VALUE));
	}

	/**
	 * Replaces {@code target} in one conditional update against {@code documentRef}.
	 * All preconditions are expressed in the filter, so the update result tells
	 * us whether the write matched without a separate read.
	 *
	 * @return true if the write matched
	 */
	protected <T> boolean replaceInDocument(
		Reference<T> target,
		BsonValue value,
		Reference<?> documentRef,
		@Nullable Reference<Identifier> precondition,
		@Nullable Identifier requiredValue
	) {
		BsonDocument filter = preconditions(target, documentRef, documentFilter(documentRef), precondition, requiredValue);
		return doUpdate(replacementDoc(target, value, documentRef), filter);
	}

	/**
	 * Creates {@code target} in one conditional update against {@code documentRef},
	 * provided the target field does not already exist.
	 *
	 * @return true if the write matched
	 */
	protected <T> boolean createInDocument(Reference<T> target, BsonValue value, Reference<?> documentRef) {
		BsonDocument filter = documentFilter(documentRef)
			.append(dottedFieldNameOf(target, documentRef), new BsonDocument("$exists", FALSE));
		filter = standardPreconditions(target, documentRef, filter);
		return doUpdate(replacementDoc(target, value, documentRef), filter);
	}

	/**
	 * Deletes {@code target} in one conditional update against {@code documentRef}.
	 *
	 * @return true if the write matched
	 */
	protected <T> boolean deleteInDocument(
		Reference<T> target,
		Reference<?> documentRef,
		@Nullable Reference<Identifier> precondition,
		@Nullable Identifier requiredValue
	) {
		BsonDocument filter = preconditions(target, documentRef, documentFilter(documentRef), precondition, requiredValue);
		return doUpdate(deletionDoc(target, documentRef), filter);
	}

	private <T> BsonDocument preconditions(
		Reference<T> target,
		Reference<?> startingRef,
		BsonDocument filter,
		@Nullable Reference<Identifier> precondition,
		@Nullable Identifier requiredValue
	) {
		if (precondition == null) {
			return standardPreconditions(target, startingRef, filter);
		} else {
			return explicitPreconditions(target, precondition, requireNonNull(requiredValue), startingRef, filter);
		}
	}

	/**
	 * An update that deliberately does nothing.
	 */
	protected static AtomicUpdateOperation noOp() {
		// Return DONE rather than NOTHING_CHANGED: the decision to do nothing is
		// already made, so there is no need to check whether the format changed.
		return _ -> DONE;
	}

	protected <T> BsonDocument standardPreconditions(Reference<T> target, Reference<?> startingRef, BsonDocument filter) {
		if (!target.path().equals(startingRef.path())) {
			String enclosingObjectKey = dottedFieldNameOf(target.enclosingReference(Object.class), startingRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
			if (isTaggedUnionCase(target)) {
				// Only the active case is present, so requiring the case field to exist
				// makes replacing an inactive case a no-op, like any other nonexistent path.
				String caseKey = dottedFieldNameOf(target, startingRef);
				filter.put(caseKey, new BsonDocument("$exists", TRUE));
				LOGGER.debug("| Precondition: {} exists", caseKey);
			}
		}
		return filter;
	}

	protected <T> BsonDocument explicitPreconditions(
		Reference<T> target,
		Reference<Identifier> precondition,
		Identifier requiredValue,
		Reference<?> startingRef,
		BsonDocument filter
	) {
		BsonDocument result = standardPreconditions(target, startingRef, filter);
		result.put(dottedFieldNameOf(precondition, startingRef),
			new BsonDocument("$eq", new BsonString(requiredValue.toString())));
		return result;
	}

	protected static void requireAcknowledged(UpdateResult result, BsonDocument filter, BsonDocument change, String description) {
		if (!result.wasAcknowledged()) {
			LOGGER.error("MongoDB write was not acknowledged");
			LOGGER.trace("Details of MongoDB write not acknowledged:\n\tFilter: {}\n\t{}: {}\n\tResult: {}", filter, description, change, result);
			throw new IllegalStateException("Mongo write was not acknowledged: " + result);
		}
	}

	/**
	 * We're required to cope with anything we might ourselves do during initialization
	 * (writing the state documents in {@link FormatDriver#writeAllState}, and the
	 * manifest that {@link MainDriver} writes), but outside that, we want to be as
	 * strict as possible so incompatible database changes don't go unnoticed.
	 */
	protected void validateManifestEvent(ChangeStreamDocument<BsonDocument> event, Manifest effectiveManifest) throws UnprocessableEventException, CollectionRewrittenException {
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
				throw new CollectionRewrittenException("Manifest indicates format has changed", event.getOperationType());
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
		// The manifest and root reads use the raw collection, so they bypass the
		// session and read the latest committed values: flush needs no session or
		// transaction.
		collection.requireNoTransaction();
		if (flushBarrierEnabled) {
			requireExpectedEpoch();
			// A single absolute deadline for the whole attempt: both waits stop at the
			// same instant, so their combined time cannot exceed flushTimeout.
			long flushDeadline = System.nanoTime() + flushTimeout.toNanos();
			awaitCaughtUpTo(barrier.awaitSubmitted(flushDeadline), flushDeadline);
		} else {
			List<FlushLock.Target> targets = readRevisionsToFlush();
			LOGGER.debug("Revisions to flush: {}", targets);
			flushLock.get().awaitAll(targets);
		}
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	/**
	 * Checks that the manifest and root still carry this driver's epoch.
	 * The manifest check is what lets the probes watch only state writes:
	 * a collection that has been rewritten or reinitialized changes the
	 * manifest's epoch, so the probe need not watch for those events.
	 * The root check covers a root deleted without replacing the collection.
	 *
	 * @throws FlushFailureException if the manifest or root is missing,
	 * unreadable, or carries another epoch
	 */
	private void requireExpectedEpoch() throws FlushFailureException {
		MongoCollection<BsonDocument> local = collection.rawCollection().withReadConcern(ReadConcern.LOCAL);
		BsonDocument manifestDocument = local
			.find(new BsonDocument("_id", MANIFEST_ID))
			.limit(1)
			.first();
		if (manifestDocument == null) {
			throw new FlushFailureException("Manifest document is missing; reloading");
		}
		Manifest manifest;
		try {
			manifest = formatter.decodeManifest(manifestDocument);
		} catch (UnrecognizedFormatException e) {
			throw new FlushFailureException("Manifest is no longer readable; reloading", e);
		}
		if (!epoch.equals(manifest.epoch())) {
			throw new FlushFailureException("Manifest epoch has changed; reloading");
		}
		BsonDocument rootDocument = local
			.find(rootDocumentsFilter())
			.limit(1)
			.first();
		if (rootDocument == null) {
			throw new FlushFailureException("Root document is missing; reloading");
		}
		BsonValue rootEpoch = rootDocument.get(DocumentFields.epoch.name());
		if (!(rootEpoch instanceof BsonString s) || !s.getValue().equals(epoch.toString())) {
			throw new FlushFailureException("Root document epoch has changed; reloading");
		}
	}

	/**
	 * Selects the change events that can advance the flush barrier in this format.
	 * A flush waits for these and ignores every other event.
	 */
	protected abstract Bson lastStateChangeFilter();

	/**
	 * Waits until {@link FlushBarrier#submittedThrough} is at or past
	 * {@code startPosition}.
	 * <p>
	 * Two probes keep the wait bounded whether the stream is quiet or busy: the
	 * backlog probe bounds it when the stream goes quiet, and the arrival probe
	 * when new events keep arriving.
	 * <p>
	 * The backlog probe starts at {@code startPosition}, so it covers any update
	 * submitted before the flush that the {@code ChangeReceiver} has not yet
	 * submitted downstream. Such an update is already in the oplog: writes use
	 * {@link com.mongodb.WriteConcern#MAJORITY}, and change streams report only
	 * majority-committed changes. An empty batch therefore means no such update
	 * is outstanding, and the last event it saw is a safe bound.
	 * <p>
	 * The arrival probe opens during the flush, so any event it sees postdates
	 * every update submitted before the flush; waiting for the later of the two
	 * bounds covers them all. If {@code startPosition} has aged out of the
	 * oplog, the backlog probe fails and the flush reloads instead of skipping
	 * events.
	 *
	 * @see <a href="https://www.mongodb.com/docs/manual/changeStreams/#event-notification">MongoDB change streams: Event Notification</a>
	 */
	private void awaitCaughtUpTo(BsonTimestamp startPosition, long deadlineNanos) throws FlushFailureException, InterruptedException {
		checkDeadline(deadlineNanos);
		MongoCollection<BsonDocument> raw = collection.rawCollection();
		try {
			try (var backlogProbe = raw.watch(List.of(
					match(and(gt("clusterTime", startPosition), lastStateChangeFilter())),
					project(include("clusterTime"))))
				.startAtOperationTime(startPosition)
				.batchSize(BACKLOG_PROBE_BATCH_SIZE)
				.maxAwaitTime(MIN_WAIT_MS, MILLISECONDS)
				.cursor()) {

				var first = backlogProbe.tryNext();
				if (first == null) {
					LOGGER.debug("Flush probe found nothing pending");
					return;
				}

				checkDeadline(deadlineNanos);
				try (var arrivalProbe = raw.watch(List.of(match(lastStateChangeFilter()), project(include("clusterTime"))))
					.maxAwaitTime(MIN_WAIT_MS, MILLISECONDS)
					.cursor()) {

					BsonTimestamp last = requireNonNull(first.getClusterTime());
					while (true) {
						checkDeadline(deadlineNanos);
						var arrival = arrivalProbe.tryNext();
						if (arrival != null) {
							barrier.awaitSubmittedThrough(latest(last, requireNonNull(arrival.getClusterTime())), deadlineNanos);
							return;
						}
						while (backlogProbe.available() > 0) {
							last = latest(last, requireNonNull(backlogProbe.next().getClusterTime()));
						}
						var event = backlogProbe.tryNext();
						if (event == null) {
							barrier.awaitSubmittedThrough(last, deadlineNanos);
							return;
						}
						last = latest(last, requireNonNull(event.getClusterTime()));
					}
				}
			}
		} catch (MongoInterruptedException e) {
			// The driver sets the interrupt flag before throwing. Clear it and
			// rethrow as an interrupt, so doRetryableDriverOperation retries
			// without disconnecting a healthy driver.
			Thread.interrupted();
			InterruptedException exception = new InterruptedException("Interrupted while probing the change stream");
			exception.initCause(e);
			throw exception;
		} catch (MongoException e) {
			throw new FlushFailureException("Flush probe failed", e);
		}
	}

	private static BsonTimestamp latest(BsonTimestamp first, BsonTimestamp second) {
		return first.compareTo(second) >= 0 ? first : second;
	}

	private static void checkDeadline(long deadlineNanos) throws FlushTimeoutException {
		if (System.nanoTime() - deadlineNanos >= 0) {
			throw new FlushTimeoutException("Timed out probing the change stream");
		}
	}

	/**
	 * The shortest poll we can ask for. A zero {@code maxAwaitTime} leaves the
	 * server's default, which for a tailable cursor is 1000ms, so the probes ask
	 * for 1ms instead.
	 */
	private static final long MIN_WAIT_MS = 1;

	/**
	 * How many events the backlog probe asks for per round trip. The loop drains whatever
	 * is available and stops at the first quiet moment, so a bigger batch would
	 * only fetch events past the point where the flush finishes.
	 */
	private static final int BACKLOG_PROBE_BATCH_SIZE = 100;

	/**
	 * Checks that {@code document} carries this driver's epoch.
	 * <p>
	 * Revisions from a different epoch are not comparable with the ones this
	 * driver has applied, so {@link #flush()} must not wait on them.
	 *
	 * @throws EpochMismatchException if it does not, meaning the collection has
	 * been reinitialized or refurbished since this driver loaded it
	 */
	protected void checkEpoch(BsonDocument document) throws EpochMismatchException {
		BsonString expected = new BsonString(epoch.toString());
		BsonValue found = document.get(DocumentFields.epoch.name());
		if (!expected.equals(found)) {
			throw new EpochMismatchException("State document " + document.getString("_id").getValue()
				+ " has epoch " + found + ", but this driver is using " + epoch);
		}
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		FlushLock lock = flushLock.get();
		if (lock != null) {
			// A driver that was created but never loaded its state has no FlushLock.
			lock.close();
		}
		barrier.close();
	}

	protected BsonDocument initialDocument(BsonValue initialState, RevisionTuple revision, BsonString documentId) {
		BsonDocument fieldValues = new BsonDocument("_id", documentId);

		fieldValues.put(DocumentFields.epoch.name(), new BsonString(epoch.toString()));
		revision.putInto(fieldValues);
		fieldValues.put(DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(context.getAttributes()));
		// The state goes last because it's the big field, and its size can change.
		fieldValues.put(DocumentFields.state.name(), initialState);

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
		Map<BsonString, RevisionTuple> documentRevisions
	){}

	/**
	 * Fails unless {@code target} is a reference that the {@link BoskDriver}
	 * contract permits deleting. Callers at the update boundary use this before
	 * planning a database operation, so a format-specific driver never sees an
	 * invalid deletion target.
	 */
	static void requireDeletable(Reference<?> target) {
		if (!target.isDeletable()) {
			throw new IllegalArgumentException("Object is not deletable: " + target);
		}
	}

	private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFormatDriver.class);

}
