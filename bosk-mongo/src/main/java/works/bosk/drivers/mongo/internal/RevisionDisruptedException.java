package works.bosk.drivers.mongo.internal;

import works.bosk.exceptions.FlushFailureException;

/**
 * A kind of {@link FlushFailureException} indicating that the driver's revision
 * tracking has been disrupted, so the {@link FlushLock} can no longer be trusted
 * and the driver must reconnect and reload the state from the database.
 * <p>
 * A disruption is only ever detected by {@link AbstractFormatDriver#flush}, the
 * only driver method that reads the revision number. Retrying that flush would fail
 * the same way every time, so the driver could only spin in a busy loop; a reconnect,
 * by contrast, cannot encounter a disruption, so it can proceed immediately.
 */
abstract class RevisionDisruptedException extends FlushFailureException {
	public RevisionDisruptedException(String message) {
		super(message);
	}

	public RevisionDisruptedException(String message, Throwable cause) {
		super(message, cause);
	}

	public RevisionDisruptedException(Throwable cause) {
		super(cause);
	}
}
