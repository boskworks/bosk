package works.bosk.drivers.mongo.internal;

/**
 * Indicates that we are unable to interpret the contents of
 * the manifest document found in the database.
 */
class UnrecognizedFormatException extends Exception {
	UnrecognizedFormatException(String message) {
		super(message);
	}

	UnrecognizedFormatException(String message, Throwable cause) {
		super(message, cause);
	}

	UnrecognizedFormatException(Throwable cause) {
		super(cause);
	}
}
