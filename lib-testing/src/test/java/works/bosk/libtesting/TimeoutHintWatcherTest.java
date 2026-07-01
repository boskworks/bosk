package works.bosk.libtesting;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutHintWatcherTest {
	TimeoutHintWatcher watcher = new TimeoutHintWatcher((ctx, key) -> Optional.of("42s"));
	ByteArrayOutputStream stderr;
	PrintStream originalErr;

	@BeforeEach
	void captureStderr() {
		originalErr = System.err;
		stderr = new ByteArrayOutputStream();
		System.setErr(new PrintStream(stderr));
	}

	@AfterEach
	void restoreStderr() {
		System.setErr(originalErr);
	}

	@Test
	void junitTimeout_printsHint() {
		watcher.testFailed(null, newTimeoutException());

		String output = stderr.toString();
		assertTrue(output.contains("JUnit timeout"), "Should mention JUnit timeout");
		assertTrue(output.contains("42s"), "Should show configured duration");
		assertTrue(output.contains("TimeoutExceptionFactory.create"), "Should include stack trace");
	}

	@Test
	void componentTimeout_printsNothing() {
		watcher.testFailed(null, new TimeoutException("pool timeout"));

		assertEquals("", stderr.toString(), "Should not print for component timeout");
	}

	@Test
	void nonTimeoutException_printsNothing() {
		watcher.testFailed(null, new RuntimeException("something else"));

		assertEquals("", stderr.toString(), "Should not print for ordinary failures");
	}

	@Test
	void wrappedJUnitTimeout_detected() {
		watcher.testFailed(null, new RuntimeException("wrapper",
			newTimeoutException()));

		assertTrue(stderr.toString().contains("JUnit timeout"),
			"Should find timeout in cause chain");
	}

	@Test
	void suppressedJUnitTimeout_detected() {
		TimeoutException suppressed = newTimeoutException();
		RuntimeException main = new RuntimeException("main");
		main.addSuppressed(suppressed);
		watcher.testFailed(null, main);

		assertTrue(stderr.toString().contains("JUnit timeout"),
			"Should find timeout in suppressed exceptions");
	}

	@Test
	void missingConfigParameter_omitsDuration() {
		TimeoutHintWatcher noConfig = new TimeoutHintWatcher((ctx, key) -> Optional.empty());
		noConfig.testFailed(null, newTimeoutException());

		String output = stderr.toString();
		assertTrue(output.contains("JUnit timeout"), "Should still print");
		assertFalse(output.contains("configured for"), "Should omit duration phrase");
	}

	/** Manual test to see what happens on timeout */
	@Disabled
	@Test
	@Timeout(1)
	@ExtendWith(TimeoutHintWatcher.class)
	void junitTimeout() throws InterruptedException {
		Thread.sleep(2000);
	}

	private static TimeoutException newTimeoutException() {
		TimeoutException result = new TimeoutException("test timed out after 42 seconds");
		result.setStackTrace(new StackTraceElement[]{
			new StackTraceElement("org.junit.jupiter.engine.extension.TimeoutExceptionFactory",
				"create", "TimeoutExceptionFactory.java", 31),
			new StackTraceElement("org.junit.jupiter.engine.extension.SameThreadTimeoutInvocation",
				"proceed", "SameThreadTimeoutInvocation.java", 64),
		});
		return result;
	}
}
