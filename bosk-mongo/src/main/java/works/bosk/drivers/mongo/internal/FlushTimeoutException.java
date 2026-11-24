package works.bosk.drivers.mongo.internal;

import works.bosk.exceptions.FlushFailureException;

/**
 * A flush timed out waiting for the {@link ChangeReceiver} to submit a change
 * downstream.
 * <p>
 * This is a retryable condition: the driver disconnects, waits for the change stream
 * to recover, and retries the flush. If it escapes the retry loop, {@link MainDriver}
 * converts it to a {@link works.bosk.exceptions.RetryableFlushException} with the
 * driver's retry hint.
 */
class FlushTimeoutException extends FlushFailureException {
	public FlushTimeoutException(String message) {
		super(message);
	}

	public FlushTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}

	public FlushTimeoutException(Throwable cause) {
		super(cause);
	}
}
