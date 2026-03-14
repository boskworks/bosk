package works.bosk.drivers.mongo.exceptions;

import works.bosk.BoskDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;

/**
 * Thrown from {@link BoskDriver#initialState} if the initial state
 * can't be loaded from the database and {@link MongoDriverSettings.InitialDatabaseUnavailableMode#FAIL_FAST FAIL_FAST}
 * is in effect.
 */
public class InitialStateFailureException extends RuntimeException {
	public InitialStateFailureException(String message) {
		super(message);
	}

	public InitialStateFailureException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialStateFailureException(Throwable cause) {
		super(cause);
	}
}
