package works.bosk.drivers.mongo.internal;

import works.bosk.BoskDriver;
import works.bosk.exceptions.FlushFailureException;

/**
 * A kind of {@link FlushFailureException} indicating that the
 * {@link Formatter.DocumentFields#revision revision} field has become unreliable;
 * for example, if it has disappeared. This makes {@link FlushLock} unreliable,
 * and so we need to reinitialize the {@link ChangeReceiver}.
 * <p>
 * This is the only time a driver method detects this sort of problem,
 * because {@link BoskDriver#flush} is the only driver method that does
 * a database read. Otherwise, these kinds of problems are always
 * detected by asynchronous change stream processing; this is somewhat analogous
 * to an {@link UnprocessableEventException} but without an "event".
 * <p>
 * Bosk users could see this exception, but they ought to be handling
 * {@link FlushFailureException} anyway, so this subclass is more of
 * an implementation detail.
 */
class RevisionFieldDisruptedException extends FlushFailureException {
	public RevisionFieldDisruptedException(String message) {
		super(message);
	}

	public RevisionFieldDisruptedException(String message, Throwable cause) {
		super(message, cause);
	}

	public RevisionFieldDisruptedException(Throwable cause) {
		super(cause);
	}
}
