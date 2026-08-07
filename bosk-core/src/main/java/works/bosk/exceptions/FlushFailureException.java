package works.bosk.exceptions;

import java.io.IOException;
import works.bosk.BoskDriver;

/**
 * Indicates that a call to {@link BoskDriver#flush()} was unable to guarantee
 * that all prior updates have been applied.
 *
 * <p>
 * This is the vehicle by which a {@link BoskDriver} reports flush failures that
 * are not already {@link IOException}s, such as unexpected database contents,
 * database timeouts, disconnections, or {@code SQLException}s. Because it extends
 * {@link IOException}, any code that already handles the failures of a method that
 * performs IO will also handle this (eg. by aborting, retrying, or logging);
 * the same is not necessarily true for {@link RuntimeException}.
 *
 * <p>
 * A driver that encounters a genuine {@link IOException} while flushing (for example,
 * from its own downstream driver) should let it propagate as-is rather than wrapping
 * it in a {@code FlushFailureException}: the caller is already expected to handle
 * {@link IOException}.
 */
public class FlushFailureException extends IOException {
	public FlushFailureException(String message) { super(message); }
	public FlushFailureException(String message, Throwable cause) { super(message, cause); }
	public FlushFailureException(Throwable cause) { super(cause); }
}
