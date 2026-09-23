package works.bosk.libtesting;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A gate that lets a test block a background operation at a deterministic point
 * until the test lets it proceed.
 * <p>
 * The background operation calls {@link #signal()} once it reaches the blocking
 * point and then {@link #awaitRelease(Duration)} to pause. The test calls
 * {@link #awaitSignal(Duration)} to wait for the operation to arrive, performs
 * whatever it needs to do while the operation is paused (usually, to commit a
 * concurrent change), and then calls {@link #release()} to let the operation
 * proceed.
 * <p>
 * Waiting ignores interrupts: a gate exists to hold an operation until the test
 * releases it, and an interrupt aimed at that operation (for example, the Mongo
 * driver's habit of interrupting its change-receiver thread) must not defeat
 * that. The interrupt status is restored before returning, so callers can still
 * observe the interrupt afterwards.
 * <p>
 * The {@code description} names the operation in failure messages, so a test
 * that hangs fails with an informative error rather than a naked timeout.
 */
public final class BlockingGate {
	private final String description;
	private final CountDownLatch signalled = new CountDownLatch(1);
	private final CountDownLatch released = new CountDownLatch(1);

	public BlockingGate(String description) {
		this.description = description;
	}

	/** Called by the operation once it reaches the blocking point. */
	public void signal() {
		signalled.countDown();
	}

	/** Called by the operation to block until the test calls {@link #release()}. */
	public void awaitRelease(Duration timeout) {
		await(released, timeout, "to be released");
	}

	/** Called by the test to wait for the operation to call {@link #signal()}. */
	public void awaitSignal(Duration timeout) {
		await(signalled, timeout, "to signal");
	}

	/** Lets the blocked operation proceed. */
	public void release() {
		released.countDown();
	}

	private void await(CountDownLatch latch, Duration timeout, String state) {
		long deadline = System.nanoTime() + timeout.toNanos();
		boolean interrupted = false;
		try {
			while (true) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0) {
					throw new AssertionError("Timed out waiting for " + description + " " + state);
				}
				try {
					if (latch.await(remainingNanos, TimeUnit.NANOSECONDS)) {
						return;
					}
				} catch (InterruptedException e) {
					// Keep waiting; see the class javadoc.
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
