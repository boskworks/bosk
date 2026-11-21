package works.bosk.drivers.mongo.internal;

import java.util.concurrent.TimeUnit;
import org.bson.BsonTimestamp;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;

import static java.lang.System.identityHashCode;
import static java.util.Objects.requireNonNull;

/**
 * Tracks the cluster time through which the format driver has submitted change
 * events downstream, and lets {@link AbstractFormatDriver#flush() flush} wait
 * for the format driver to reach a given cluster time.
 * <p>
 * The receiver calls {@link #markSubmitted} with an event's cluster time only
 * after it has submitted that event's changes downstream, if any. For a
 * transaction, it marks the cluster time of the event that completes the tree, once
 * the whole tree's changes have been submitted.
 * <p>
 * This assumes the state collection is unsharded, so all of its events come from
 * one oplog, whose entries have distinct, increasing times. On a sharded
 * collection, cluster times no longer order all events, so {@code flush()} is
 * best-effort.
 *
 * @see <a href="https://github.com/boskworks/bosk/issues/567">issue #567</a>
 */
class FlushBarrier {
	/**
	 * The latest cluster time through which changes have been submitted
	 * downstream, or null until the first position is marked.
	 */
	private @Nullable BsonTimestamp submittedThrough;
	private boolean closed;
	private int waiterCount;

	FlushBarrier() {
		LOGGER.debug("New flush barrier [{}]", identityHashCode(this));
	}

	/**
	 * Records that everything through {@code position} has been submitted
	 * downstream, waking any flush waiting for such a cluster time. The first
	 * call on a freshly published driver is the initial state's position; the
	 * receiver thread guarantees that no event is marked before it.
	 * <p>
	 * This assumes the change stream delivers events in non-decreasing
	 * cluster-time order, so nothing older can still arrive.
	 */
	synchronized void markSubmitted(BsonTimestamp submittedThrough) {
		if (closed) {
			LOGGER.debug("Closed FlushBarrier ignoring cluster time {} [{}]", submittedThrough, identityHashCode(this));
			return;
		}
		if (this.submittedThrough == null || submittedThrough.compareTo(this.submittedThrough) > 0) {
			LOGGER.trace("Submitted through {} [{}]", submittedThrough, identityHashCode(this));
			this.submittedThrough = submittedThrough;
			notifyAll();
		}
	}

	/**
	 * Waits until a position has been submitted, then returns it. The first
	 * position is the initial state's, so this waits until a flush may start.
	 */
	synchronized BsonTimestamp awaitSubmitted(long deadlineNanos) throws InterruptedException, FlushTimeoutException {
		while (submittedThrough == null) {
			throwIfClosed();
			awaitUntil(deadlineNanos, "a submitted position");
		}
		return requireNonNull(submittedThrough);
	}

	/**
	 * Waits until the receiver has submitted every event at or before {@code target},
	 * using the caller's deadline.
	 */
	synchronized void awaitSubmittedThrough(BsonTimestamp target, long deadlineNanos) throws InterruptedException, FlushTimeoutException {
		while (submittedThrough == null || submittedThrough.compareTo(target) < 0) {
			throwIfClosed();
			awaitUntil(deadlineNanos, "submitted cluster time " + target + " (seen: " + submittedThrough + ")");
		}
		LOGGER.debug("Cluster time {} is satisfied by {} [{}]", target, submittedThrough, identityHashCode(this));
	}

	private void awaitUntil(long deadline, String description) throws InterruptedException, FlushTimeoutException {
		long remainingNanos = deadline - System.nanoTime();
		if (remainingNanos <= 0) {
			throw new FlushTimeoutException("Timed out waiting for " + description);
		}
		waiterCount++;
		try {
			TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
		} finally {
			waiterCount--;
		}
	}

	private void throwIfClosed() {
		if (closed) {
			throw new DisconnectedException("FlushBarrier was closed while waiting");
		}
	}

	synchronized void close() {
		LOGGER.debug("Closing [{}]", identityHashCode(this));
		closed = true;
		notifyAll();
	}

	/**
	 * @return the number of threads currently blocked in this barrier
	 */
	synchronized int waiterCount() {
		return waiterCount;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushBarrier.class);
}
