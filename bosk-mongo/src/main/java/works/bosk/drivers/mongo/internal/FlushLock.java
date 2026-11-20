package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.bson.BsonString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;

import static java.lang.System.identityHashCode;

/**
 * Tracks, for every state document, how much of its history has been applied
 * downstream, and lets {@link AbstractFormatDriver#flush() flush} wait for
 * revisions that have not yet been applied.
 *
 * <p>
 * Each state document has its own revision number, paired with an incarnation
 * that identifies one lifetime of that number, so unrelated updates advance
 * independent counters. A flush therefore reads every document's revision
 * number and waits for each one to catch up. A target is satisfied when its
 * incarnation differs from the applied one, which means the target belongs to
 * a lifetime that has already been superseded, or when both share an
 * incarnation and the applied revision is at least the target revision. A
 * re-created document gets a fresh incarnation, so its reset revision counter
 * cannot be mistaken for an old revision of the previous incarnation.
 */
class FlushLock {
	/**
	 * One document whose applied state the flush must wait for.
	 */
	record Target(BsonString documentId, RevisionTuple revision) {}

	private final Duration flushTimeout;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition changed = lock.newCondition();

	// All of the following are guarded by lock.
	private final Map<BsonString, RevisionTuple> applied = new HashMap<>();
	private boolean isClosed;
	private int waiters;

	/**
	 * @param applied the revisions the new lock should consider already applied;
	 * a load typically passes each loaded revision decremented by one, so that a
	 * flush waits until {@link AbstractFormatDriver#onHasBeenApplied} advances it
	 */
	public FlushLock(Map<BsonString, RevisionTuple> applied, Duration flushTimeout) {
		LOGGER.debug("New flush lock [{}]", identityHashCode(this));
		this.flushTimeout = flushTimeout;
		this.applied.putAll(applied);
	}

	/**
	 * @return true if the given document has already applied a change event with
	 * this revision number in the same incarnation, so a change event carrying it
	 * can be ignored
	 */
	boolean alreadySeen(BsonString documentId, RevisionTuple revision) {
		lock.lock();
		try {
			RevisionTuple seen = applied.get(documentId);
			return seen != null
				&& seen.sameIncarnationAs(revision)
				&& seen.revision() >= revision.revision();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * @return the document's latest applied incarnation, or null if none has
	 * been applied yet; useful for a change event that does not itself carry
	 * the incarnation because it only bumped the revision
	 */
	String currentIncarnation(BsonString documentId) {
		lock.lock();
		try {
			RevisionTuple seen = applied.get(documentId);
			return seen == null ? null : seen.incarnation();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Records that the given document's state has been applied downstream, and
	 * wakes any flush waiting for it.
	 */
	void finished(BsonString documentId, RevisionTuple revision) {
		lock.lock();
		try {
			if (isClosed) {
				LOGGER.debug("Closed FlushLock ignoring revision {} for {} [{}]", revision, documentId, identityHashCode(this));
				return;
			}
			mergeAppliedRevision(documentId, revision);
			changed.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Records that the given documents' states have all been applied downstream,
	 * waking flush waiters once.
	 */
	void finishedAll(Map<BsonString, RevisionTuple> revisions) {
		lock.lock();
		try {
			if (isClosed) {
				LOGGER.debug("Closed FlushLock ignoring {} revisions [{}]", revisions.size(), identityHashCode(this));
				return;
			}
			revisions.forEach(this::mergeAppliedRevision);
			changed.signalAll();
		} finally {
			lock.unlock();
		}
	}

	private void mergeAppliedRevision(BsonString documentId, RevisionTuple revision) {
		applied.merge(documentId, revision, (existing, candidate) ->
			existing.sameIncarnationAs(candidate) && existing.revision() >= candidate.revision()
				? existing
				: candidate);
	}

	/**
	 * Waits until every target has been applied, examining the targets in the
	 * order given so that callers can wait on ancestors before descendants.
	 */
	void awaitAll(List<Target> targets) throws InterruptedException, FlushTimeoutException {
		long deadline = System.nanoTime() + flushTimeout.toNanos();
		lock.lock();
		try {
			for (Target target: targets) {
				while (true) {
					if (isClosed) {
						// This happens if the FormatDriver is defunct already,
						// so DisconnectedException is more appropriate than FlushFailureException
						// because it won't provoke a bunch of error handling (including disconnection)
						// that is evidently already happening.
						throw new DisconnectedException("FlushLock was closed while waiting");
					}
					RevisionTuple seen = applied.get(target.documentId());
					if (seen != null
						&& (!seen.sameIncarnationAs(target.revision())
							|| seen.revision() >= target.revision().revision())) {
						LOGGER.debug("Revision {} for {} is satisfied by {} [{}]",
							target.revision(), target.documentId(), seen, identityHashCode(this));
						break;
					}

					long remainingNanos = deadline - System.nanoTime();
					if (remainingNanos <= 0) {
						throw new FlushTimeoutException("Timed out waiting for revision "
							+ target.revision() + " for " + target.documentId()
							+ " (seen: " + seen + ")");
					}
					LOGGER.debug("Awaiting revision {} for {} (seen: {}) [{}]",
						target.revision(), target.documentId(), seen, identityHashCode(this));
					waiters++;
					try {
						changed.awaitNanos(remainingNanos);
					} finally {
						waiters--;
					}
				}
			}
		} finally {
			lock.unlock();
		}
	}

	public void close() {
		lock.lock();
		try {
			LOGGER.debug("Closing [{}]", identityHashCode(this));
			isClosed = true;
			changed.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * @return the number of threads currently blocked in {@link #awaitAll}
	 */
	int waiterCount() {
		lock.lock();
		try {
			return waiters;
		} finally {
			lock.unlock();
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushLock.class);
}
