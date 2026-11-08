package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.internal.FlushLock.Target;

class FlushLockTest {
	private static final BsonString DOC1 = new BsonString("|a");
	private static final BsonString DOC2 = new BsonString("|b");
	private static final String INCARNATION = "incarnation";
	private static final String OTHER_INCARNATION = "other-incarnation";

	@Test
	void awaitAll_alreadySeenRevision_returnsImmediately() throws InterruptedException, FlushTimeoutException {
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(DOC1, new RevisionTuple(INCARNATION, 100)), Duration.ofSeconds(5));

		lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 50))));

		assertEquals(0, lock.waiterCount(), "Waiting on an already-seen revision must not block");
	}

	@Test
	void awaitAll_differentIncarnation_isSatisfied() throws InterruptedException, FlushTimeoutException {
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(DOC1, new RevisionTuple(OTHER_INCARNATION, 1)), Duration.ofSeconds(5));

		lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 999))));

		assertEquals(0, lock.waiterCount(), "A target from a superseded incarnation is in the past");
	}

	@Test
	void awaitAll_timeout_throwsFlushTimeoutException() {
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(), Duration.ofMillis(10));
		assertThrows(FlushTimeoutException.class,
			() -> lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 10)))));
	}

	@Test
	void awaitAll_higherRevisionInSameIncarnation_waits() {
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(DOC1, new RevisionTuple(INCARNATION, 999)), Duration.ofMillis(10));

		assertThrows(FlushTimeoutException.class,
			() -> lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 1000)))));
	}

	@Test
	void awaitAll_recreatedDocument_waitsForNewIncarnationRevision() {
		// The old incarnation had a higher revision than the re-created
		// document will reach. A target from the new incarnation must still
		// wait for the new incarnation's revision.
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(DOC1, new RevisionTuple(OTHER_INCARNATION, 5)), Duration.ofMillis(10));
		lock.finished(DOC1, new RevisionTuple(INCARNATION, 1));
		assertThrows(FlushTimeoutException.class,
			() -> lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 2)))),
			"A re-created document must not be satisfied by the old incarnation's revision");
	}

	@Test
	void awaitAll_newRevision_wakesWhenFinished() throws InterruptedException {
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(), Duration.ofSeconds(5));
		Thread awaiting = new Thread(() -> {
			try {
				lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 10))));
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		awaiting.start();
		awaitWaiterEnqueued(lock, awaiting);
		lock.finished(DOC1, new RevisionTuple(INCARNATION, 10));
		awaiting.join(5000);
		assertFalse(awaiting.isAlive(), "Waiting thread must wake when the revision finishes");
		assertEquals(0, lock.waiterCount(), "No waiters must remain after the revision finishes");
	}

	@Test
	void awaitAll_multipleDocuments_waitsForEach() throws InterruptedException {
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(), Duration.ofSeconds(5));
		Thread awaiting = new Thread(() -> {
			try {
				lock.awaitAll(List.of(
					new Target(DOC1, new RevisionTuple(INCARNATION, 10)),
					new Target(DOC2, new RevisionTuple(INCARNATION, 20))));
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		awaiting.start();
		awaitWaiterEnqueued(lock, awaiting);

		lock.finished(DOC1, new RevisionTuple(INCARNATION, 10));
		lock.finished(DOC2, new RevisionTuple(INCARNATION, 20));
		awaiting.join(5000);
		assertFalse(awaiting.isAlive(), "Waiting thread must wake when all revisions finish");
	}

	@Test
	void awaitAll_lockClosedWhileWaiting_throwsDisconnectedException() throws InterruptedException {
		// Regression test for https://github.com/boskworks/bosk/issues/329:
		// when publishFormatDriver closes the old format driver's FlushLock,
		// a thread waiting in awaitAll must throw DisconnectedException
		// rather than silently succeeding as though the revision had arrived.
		FlushLock lock = new FlushLock(Optional.empty(), Map.of(), Duration.ofSeconds(5));
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread awaiting = new Thread(() -> {
			try {
				lock.awaitAll(List.of(new Target(DOC1, new RevisionTuple(INCARNATION, 10))));
				failure.set(new AssertionError("awaitAll must not return after the lock is closed"));
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
		assertNull(failure.get(), "awaitAll must throw DisconnectedException when the lock is closed");
	}

	private static void awaitWaiterEnqueued(FlushLock lock, Thread awaiting) throws InterruptedException {
		// The waiter records itself just before blocking, so once it's recorded,
		// close() and finished() are guaranteed to signal it. Waiting for the
		// record ensures the test exercises the "closed while waiting" check,
		// rather than the isClosed check at the start, which would mask a
		// regression of the former.
		long deadline = System.nanoTime() + ENQUEUE_TIMEOUT.toNanos();
		while (awaiting.isAlive() && System.nanoTime() < deadline) {
			if (lock.waiterCount() == 1) {
				return;
			}
			Thread.sleep(POLL_INTERVAL_MS);
		}
		throw new AssertionError("Waiting thread did not start waiting");
	}

	private static final Duration ENQUEUE_TIMEOUT = Duration.ofSeconds(5);
	private static final long POLL_INTERVAL_MS = 10;
}
