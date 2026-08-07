package works.bosk.drivers.mongo.internal;

import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.exceptions.FlushFailureException;

import static java.lang.System.identityHashCode;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Implements waiting mechanism for revision numbers.
 *
 * <p>
 * The <code>epoch</code> identifies a particular initialization of the database collection.
 * It is generated when the collection is first initialized, and preserved
 * when the collection is reinitialized with the same state (eg. by a refurbish operation).
 * It is NOT preserved when a different process deletes the collection and starts over,
 * because the start-over is a genuinely new initialization.
 *
 * <p>
 * The reason for the epoch is to detect the scenario where a document/collection/database
 * is deleted, and then a new bosk reinitializes the database with a * different state.
 * The revision number alone is an insufficient signal in this case: the reinitialized
 * collection may very well have the same revision number as what we've already seen,
 * in which case the flush would wrongly conclude that there is no need to wait.
 * Hence, we also track the epoch: a flush needs to wait unless it has already seen
 * both the current <code>epoch</code> and the current <code>revision</code> number.
 */
class FlushLock {
	private final long flushTimeoutMS;
	private final Lock queueLock = new ReentrantLock();
	private final PriorityBlockingQueue<Waiter> queue = new PriorityBlockingQueue<>();
	private final Optional<BsonString> epoch;
	private volatile long alreadySeen;
	private boolean isClosed;

	/**
	 * @param epoch the exact epoch from the database; {@link Optional#empty()} indicates a
	 * legacy database that predates the epoch mechanism, and matches only another empty epoch.
	 * @param revisionAlreadySeen the exact revision from the database:
	 * too old, and we'll wait forever for intervening revisions that have already happened;
	 * too new, and we'll proceed immediately without waiting for revisions that haven't happened yet.
	 */
	public FlushLock(Optional<BsonString> epoch, long revisionAlreadySeen, long flushTimeoutMS) {
		LOGGER.debug("New flush lock at epoch {} revision {} [{}]", epoch, revisionAlreadySeen, identityHashCode(this));
		this.flushTimeoutMS = flushTimeoutMS;
		this.epoch = epoch;
		this.alreadySeen = revisionAlreadySeen;
	}

	/**
	 * @return true if the lock and the database agree on the collection's epoch:
	 * either both have the same epoch, or both are legacy databases with no epoch
	 */
	boolean epochMatches(Optional<BsonString> databaseEpoch) {
		return epoch.equals(databaseEpoch);
	}

	/**
	 * @return the epoch this lock was created for
	 */
	Optional<BsonString> epoch() {
		return epoch;
	}

	private record Waiter(
		long revision,
		Semaphore semaphore
	) implements Comparable<Waiter> {
		@Override
		public int compareTo(Waiter other) {
			return Long.compare(revision, other.revision);
		}
	}

	boolean alreadySeen(BsonInt64 revision) {
		return revision.longValue() <= alreadySeen;
	}

	void awaitRevision(BsonInt64 revision) throws InterruptedException, FlushFailureException {
		long revisionValue = revision.longValue();
		Semaphore semaphore = new Semaphore(0);
		long past;
		try {
			queueLock.lock();
			if (isClosed) {
				throw new DisconnectedException("FlushLock is closed");
			}
			queue.add(new Waiter(revisionValue, semaphore));
			past = alreadySeen;
		} finally {
			queueLock.unlock();
		}
		if (revisionValue > past) {
			LOGGER.debug("Awaiting revision {} > {} [{}]", revisionValue, past, identityHashCode(this));
			if (!semaphore.tryAcquire(flushTimeoutMS, MILLISECONDS)) {
				throw new FlushFailureException("Timed out waiting for revision " + revisionValue + " > " + alreadySeen);
			}
			if (isClosed) {
				// Can't simply return and pretend this worked
				throw new DisconnectedException("FlushLock was closed while waiting");
			}
			LOGGER.debug("Done awaiting revision {} [{}]", revisionValue, identityHashCode(this));
		} else {
			LOGGER.debug("Revision {} <= {} is in the past; don't wait [{}]", revisionValue, past, identityHashCode(this));
		}
	}

	/**
	 * Called after updates are sent downstream.
	 * @param revision can be null
	 */
	void finishedRevision(BsonInt64 revision) {
		if (revision == null) {
			return;
		}

		try {
			queueLock.lock();
			long revisionValue = revision.longValue();
			if (isClosed) {
				LOGGER.debug("Closed FlushLock ignoring revision {} [{}]", revisionValue, identityHashCode(this));
				return;
			}
			if (revisionValue <= alreadySeen) {
				LOGGER.debug("Note: revision did not advance: {} <= {} [{}]", revisionValue, alreadySeen, identityHashCode(this));
			}

			do {
				Waiter w = queue.peek();
				if (w == null || w.revision > revisionValue) {
					break;
				} else {
					Waiter removed = queue.remove();
					assert w == removed;
					w.semaphore.release();
				}
			} while (true);

			if (revisionValue > alreadySeen) {
				alreadySeen = revisionValue;
			}
			LOGGER.debug("Finished {} [{}]", revisionValue, identityHashCode(this));
		} finally {
			queueLock.unlock();
		}
	}

	public void close() {
		try {
			queueLock.lock();
			LOGGER.debug("Closing [{}]", identityHashCode(this));
			isClosed = true;
			Waiter w;
			while ((w = queue.poll()) != null) {
				w.semaphore.release();
			}
		} finally {
			queueLock.unlock();
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushLock.class);
}
