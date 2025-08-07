package works.bosk.drivers.mongo.exceptions;

import works.bosk.BoskDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;

/**
 * Thrown from {@link BoskDriver#initialRoot} if the initial root
 * can't be loaded from the database and {@link MongoDriverSettings.InitialDatabaseUnavailableMode#FAIL}
 * is in effect.
 */
public class InitialRootFailureException extends RuntimeException {
	public InitialRootFailureException(String message) {
		super(message);
	}

	public InitialRootFailureException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialRootFailureException(Throwable cause) {
		super(cause);
	}
}
