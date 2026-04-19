package works.bosk.drivers.mongo.internal;

import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;

/**
 * The collection contents don't obey the rules of the format declared by the manifest document.
 */
public class InvalidCollectionContentsException extends Exception {
	InvalidCollectionContentsException(DatabaseFormat format, String details) {
		super(message(format, details));
	}

	InvalidCollectionContentsException(DatabaseFormat format, String details, Throwable cause) {
		super(message(format, details), cause);
	}

	private static String message(DatabaseFormat format, String details) {
		return "Collection contents don't match " + format.name() + " format: " + details;
	}
}
