package works.bosk.drivers.mongo.internal;

import works.bosk.BoskDriver;

/**
 * Thrown from {@link ChangeListener#onConnectionSucceeded()} to indicate that the
 * {@link BoskDriver#initialRoot} logic failed.
 */
class InitialRootActionException extends Exception {
	public InitialRootActionException(String message) {
		super(message);
	}

	public InitialRootActionException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialRootActionException(Throwable cause) {
		super(cause);
	}
}
