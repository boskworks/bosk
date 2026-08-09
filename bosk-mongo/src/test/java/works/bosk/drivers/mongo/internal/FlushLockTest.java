package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonInt64;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.exceptions.FlushFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlushLockTest {

	@Test
	void awaitRevision_alreadySeenRevision_doesNotEnqueueWaiter() throws InterruptedException, FlushFailureException {
		FlushLock lock = new FlushLock(Optional.empty(), 100, 5000);
		lock.awaitRevision(new BsonInt64(50)); // already seen; returns immediately
		assertEquals(0, lock.queue.size(), "Waiting on an already-seen revision must not enqueue a waiter");
	}

	@Test
	void awaitRevision_newRevision_wakesWhenFinished() throws InterruptedException {
		FlushLock lock = new FlushLock(Optional.empty(), 0, 5000);
		Thread awaiting = new Thread(() -> {
			try {
				lock.awaitRevision(new BsonInt64(10));
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		awaiting.start();
		lock.finishedRevision(new BsonInt64(10));
		awaiting.join(5000);
		assertFalse(awaiting.isAlive(), "Waiting thread must wake when the revision finishes");
		assertEquals(0, lock.queue.size(), "Queue must drain after the revision finishes");
	}

	@Test
	void awaitRevision_lockClosedWhileWaiting_throwsDisconnectedException() throws InterruptedException {
		// Regression test for https://github.com/boskworks/bosk/issues/329:
		// when publishFormatDriver closes the old format driver's FlushLock,
		// a thread waiting in awaitRevision must throw DisconnectedException
		// rather than silently succeeding as though the revision had arrived.
		FlushLock lock = new FlushLock(Optional.empty(), 0, 5000);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread awaiting = new Thread(() -> {
			try {
				lock.awaitRevision(new BsonInt64(10));
				failure.set(new AssertionError("awaitRevision must not return after the lock is closed"));
			} catch (DisconnectedException expected) {
				// Expected: the disconnection must not be swallowed
			} catch (Exception e) {
				failure.set(new AssertionError("Expected DisconnectedException but got " + e, e));
			}
		});
		awaiting.start();
		awaitWaiterEnqueued(lock, awaiting);
		lock.close();
		awaiting.join(5000);
		assertFalse(awaiting.isAlive(), "Waiting thread must wake when the lock is closed");
		assertNull(failure.get(), "awaitRevision must throw DisconnectedException when the lock is closed");
	}

	private static void awaitWaiterEnqueued(FlushLock lock, Thread awaiting) throws InterruptedException {
		// The waiter adds itself to the queue just before blocking, so once it's
		// enqueued, close() is guaranteed to release it. Waiting for the enqueue
		// ensures the test exercises the "closed while waiting" check after
		// awaitRevision wakes, rather than the isClosed check at its start,
		// which would mask a regression of the former.
		long deadline = System.nanoTime() + ENQUEUE_TIMEOUT.toNanos();
		while (awaiting.isAlive() && System.nanoTime() < deadline) {
			if (lock.queue.size() == 1) {
				return;
			}
			Thread.sleep(POLL_INTERVAL_MS);
		}
		throw new AssertionError("Waiting thread did not enqueue its Waiter");
	}

	private static final Duration ENQUEUE_TIMEOUT = Duration.ofSeconds(5);
	private static final long POLL_INTERVAL_MS = 10;
}
