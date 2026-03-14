package works.bosk.drivers.mongo.internal;

import works.bosk.BoskDriver;

/**
 * Thrown from {@link ChangeListener#onConnectionSucceeded()} to indicate that the
 * {@link BoskDriver#initialState} logic failed.
 */
class InitialStateActionException extends Exception {
	public InitialStateActionException(String message) {
		super(message);
	}

	public InitialStateActionException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialStateActionException(Throwable cause) {
		super(cause);
	}
}
