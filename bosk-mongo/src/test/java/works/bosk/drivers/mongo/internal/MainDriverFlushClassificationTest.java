package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.RetryableFlushException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class MainDriverFlushClassificationTest {
	private static final Duration RETRY_AFTER = Duration.ofSeconds(42);

	@Test
	void flushTimeout_becomesRetryableFlush() {
		FlushTimeoutException e = new FlushTimeoutException("Timed out");
		RetryableFlushException result = assertInstanceOf(RetryableFlushException.class,
			MainDriver.classifyFlushFailure(e, RETRY_AFTER));
		assertEquals(RETRY_AFTER, result.retryAfter());
	}

	@Test
	void plainFlushFailure_staysFlushFailure() {
		FlushFailureException e = new FlushFailureException("Read failed");
		assertSame(e, MainDriver.classifyFlushFailure(e, RETRY_AFTER));
	}

	@Test
	void retryableFlush_staysRetryableFlush() {
		RetryableFlushException e = new RetryableFlushException("Retry later", RETRY_AFTER);
		assertSame(e, MainDriver.classifyFlushFailure(e, RETRY_AFTER));
	}

	@Test
	void plainIOException_staysIOException() {
		IOException e = new IOException("I/O");
		assertSame(e, MainDriver.classifyFlushFailure(e, RETRY_AFTER));
	}

	@Test
	void disconnectedException_becomesRetryableFlush() {
		DisconnectedException e = new DisconnectedException("Disconnected");
		RetryableFlushException result = assertInstanceOf(RetryableFlushException.class,
			MainDriver.classifyFlushFailure(e, RETRY_AFTER));
		assertEquals(RETRY_AFTER, result.retryAfter());
	}

	@Test
	void immediateReconnect_staysFlushFailure() {
		ImmediateReconnectException e = new ImmediateReconnectException(new IllegalStateException("Disrupted"));
		FlushFailureException result = assertInstanceOf(FlushFailureException.class,
			MainDriver.classifyFlushFailure(e, RETRY_AFTER));
		assertFalse(result instanceof RetryableFlushException,
			"An immediate-reconnect condition must not become a retryable flush");
	}

	@Test
	void otherRuntimeException_becomesFlushFailure() {
		IllegalStateException e = new IllegalStateException("Bug");
		FlushFailureException result = assertInstanceOf(FlushFailureException.class,
			MainDriver.classifyFlushFailure(e, RETRY_AFTER));
		assertFalse(result instanceof RetryableFlushException,
			"A programming error must not become a retryable flush");
	}
}
