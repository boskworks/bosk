package works.bosk.drivers.mongo.internal;

import works.bosk.exceptions.FlushFailureException;

/**
 * A kind of {@link FlushFailureException} indicating that the database collection's
 * {@link Formatter.DocumentFields#epoch epoch} does not match the epoch that
 * {@link AbstractFormatDriver}'s {@link FlushLock} was created for,
 * meaning that {@link Formatter.DocumentFields#revision revision} numbers are no longer comparable.
 * This makes the {@link FlushLock} unreliable, and so we need to reload
 * the state from the database and reinitialize the {@link ChangeReceiver}.
 */
class EpochMismatchException extends FlushFailureException {
	public EpochMismatchException(String message) {
		super(message);
	}
}
