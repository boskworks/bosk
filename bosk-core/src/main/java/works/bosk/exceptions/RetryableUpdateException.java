package works.bosk.exceptions;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * Indicates that an update could not be completed because the driver could not
 * win a transient transaction conflict within its retry budget, but a later
 * attempt may succeed.
 * <p>
 * This is the update counterpart to {@link RetryableFlushException}. It is
 * unchecked because update methods such as
 * {@link works.bosk.BoskDriver#submitReplacement} do not declare checked
 * exceptions. {@link #retryAfter()} is an estimate of how long to wait before
 * retrying; it is a hint, not a promise that a retry will succeed.
 */
public class RetryableUpdateException extends RuntimeException {
	private final Duration retryAfter;

	public RetryableUpdateException(String message, Duration retryAfter) {
		super(message);
		this.retryAfter = requireNonNull(retryAfter);
	}

	public RetryableUpdateException(String message, Throwable cause, Duration retryAfter) {
		super(message, cause);
		this.retryAfter = requireNonNull(retryAfter);
	}

	public RetryableUpdateException(Throwable cause, Duration retryAfter) {
		super(cause);
		this.retryAfter = requireNonNull(retryAfter);
	}

	/**
	 * @return the driver's estimate of how long to wait before retrying
	 */
	public Duration retryAfter() {
		return retryAfter;
	}
}
