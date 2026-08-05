package works.bosk.libtesting;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingGateTest {

	@Test
	void awaitSignal_afterSignal_returnsPromptly() {
		BlockingGate gate = new BlockingGate("the operation");
		gate.signal();
		assertDoesNotThrow(() -> gate.awaitSignal(Duration.ofSeconds(5)));
	}

	@Test
	void awaitSignal_withoutSignal_timesOut() {
		BlockingGate gate = new BlockingGate("the slow operation");
		AssertionError error = assertThrows(AssertionError.class, () -> gate.awaitSignal(Duration.ofMillis(100)));
		assertTrue(error.getMessage().contains("the slow operation"), "Failure message should name the operation");
	}

	@Test
	void awaitRelease_blocksUntilReleased() throws Exception {
		BlockingGate gate = new BlockingGate("the operation");
		AtomicBoolean proceeded = new AtomicBoolean(false);
		Thread operation = new Thread(() -> {
			gate.signal();
			gate.awaitRelease(Duration.ofSeconds(30));
			proceeded.set(true);
		});
		operation.start();
		gate.awaitSignal(Duration.ofSeconds(5));
		assertFalse(proceeded.get(), "Operation should still be blocked before release");
		gate.release();
		operation.join(5_000);
		assertFalse(operation.isAlive(), "Operation should have proceeded after release");
		assertTrue(proceeded.get(), "Operation should have run to completion after release");
	}

	@Test
	void awaitRelease_withoutRelease_timesOut() {
		BlockingGate gate = new BlockingGate("the stuck operation");
		assertThrows(AssertionError.class, () -> gate.awaitRelease(Duration.ofMillis(100)));
	}

	@Test
	void awaitRelease_interrupted_rethrowsAndReinterrupts() throws Exception {
		BlockingGate gate = new BlockingGate("the operation");
		AtomicReference<Throwable> error = new AtomicReference<>();
		AtomicBoolean reinterrupted = new AtomicBoolean(false);
		Thread operation = new Thread(() -> {
			gate.signal();
			try {
				gate.awaitRelease(Duration.ofSeconds(30));
			} catch (Throwable t) {
				error.set(t);
				reinterrupted.set(Thread.currentThread().isInterrupted());
			}
		});
		operation.start();
		gate.awaitSignal(Duration.ofSeconds(5));
		operation.interrupt();
		operation.join(5_000);
		assertFalse(operation.isAlive(), "Interrupted operation should exit");
		assertInstanceOf(AssertionError.class, error.get(), "Interruption should surface as an AssertionError");
		assertTrue(reinterrupted.get(), "The operation's interrupt status should be restored");
	}
}
