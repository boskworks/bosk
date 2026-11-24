package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bson.BsonTimestamp;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlushBarrierTest {
	private static final Duration SHORT_TIMEOUT = Duration.ofMillis(10);
	private static final Duration LONG_TIMEOUT = Duration.ofMillis(500);

	private static BsonTimestamp ts(int seconds) {
		return new BsonTimestamp(seconds, 0);
	}

	private static long shortDeadline() {
		return System.nanoTime() + SHORT_TIMEOUT.toNanos();
	}

	private static long longDeadline() {
		return System.nanoTime() + LONG_TIMEOUT.toNanos();
	}

	@Test
	void noPositionUntilMarked() {
		FlushBarrier barrier = new FlushBarrier();

		assertThrows(FlushTimeoutException.class, () -> barrier.awaitSubmitted(shortDeadline()));
	}

	@Test
	void markSubmittedSetsThePosition() throws Exception {
		FlushBarrier barrier = new FlushBarrier();
		barrier.markSubmitted(ts(10));

		assertEquals(ts(10), barrier.awaitSubmitted(longDeadline()));
	}

	@Test
	void markSubmittedAdvancesAndWakesWaiters() throws Exception {
		FlushBarrier barrier = new FlushBarrier();
		barrier.markSubmitted(ts(10));

		CountDownLatch done = new CountDownLatch(1);
		Thread waiter = Thread.ofPlatform().start(() -> {
			try {
				barrier.awaitSubmittedThrough(ts(20), longDeadline());
				done.countDown();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});

		awaitWaiterEnqueued(barrier);

		barrier.markSubmitted(ts(20));
		assertTrue(done.await(1, TimeUnit.SECONDS));
		waiter.join();
	}

	@Test
	void markSubmittedIsMonotone() throws Exception {
		FlushBarrier barrier = new FlushBarrier();
		barrier.markSubmitted(ts(10));
		barrier.markSubmitted(ts(20));
		barrier.markSubmitted(ts(15));

		assertEquals(ts(20), barrier.awaitSubmitted(longDeadline()));
		barrier.awaitSubmittedThrough(ts(20), longDeadline());
		assertThrows(FlushTimeoutException.class, () -> barrier.awaitSubmittedThrough(ts(21), shortDeadline()));
	}

	@Test
	void markSubmittedAfterClose_isIgnored() throws Exception {
		FlushBarrier barrier = new FlushBarrier();
		barrier.markSubmitted(ts(10));
		barrier.close();
		barrier.markSubmitted(ts(20));

		assertEquals(ts(10), barrier.awaitSubmitted(longDeadline()));
	}

	@Test
	void closeWakesWaitersWithDisconnectedException() throws Exception {
		FlushBarrier barrier = new FlushBarrier();
		barrier.markSubmitted(ts(10));

		CountDownLatch failed = new CountDownLatch(1);
		Thread waiter = Thread.ofPlatform().start(() -> {
			try {
				barrier.awaitSubmittedThrough(ts(20), longDeadline());
				throw new AssertionError("Should have thrown");
			} catch (DisconnectedException e) {
				failed.countDown();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});

		awaitWaiterEnqueued(barrier);
		barrier.close();
		assertTrue(failed.await(1, TimeUnit.SECONDS));
		waiter.join();
	}

	private static void awaitWaiterEnqueued(FlushBarrier barrier) throws InterruptedException {
		long deadline = System.nanoTime() + SECONDS.toNanos(5);
		while (barrier.waiterCount() == 0) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out waiting for a flush waiter to block");
			}
			Thread.sleep(1);
		}
	}

	@Test
	void awaitSubmittedThroughReturnsImmediatelyWhenAlreadySatisfied() throws Exception {
		FlushBarrier barrier = new FlushBarrier();
		barrier.markSubmitted(ts(10));
		barrier.markSubmitted(ts(30));

		barrier.awaitSubmittedThrough(ts(20), longDeadline());
		barrier.awaitSubmittedThrough(ts(30), longDeadline());
	}
}
