package works.bosk.drivers.mongo.internal;

import works.bosk.drivers.mongo.MongoDriverSettings;

/**
 * Indicates that an error was found in a
 * {@link MongoDriverSettings.DatabaseFormat DatabaseFormat} object.
 */
class FormatMisconfigurationException extends IllegalArgumentException {
	public FormatMisconfigurationException(String s) {
		super(s);
	}

	public FormatMisconfigurationException(String message, Throwable cause) {
		super(message, cause);
	}

	public FormatMisconfigurationException(Throwable cause) {
		super(cause);
	}
}
