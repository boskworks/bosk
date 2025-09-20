package works.bosk.drivers.mongo.internal;

/**
 * Indicates that the database has been found to be in a state that
 * could be considered "uninitialized", in the sense that we are permitted
 * to respond by automatically initializing the database.
 * <p>
 * This is different from discovering that the database is in some unexpected state,
 * in which case other exceptions may be thrown, and the driver would likely disconnect
 * rather than overwrite the database contents.
 * Rather, we must have a fairly high degree of certainty that we're ok to start fresh.
 */
public class UninitializedCollectionException extends Exception {
	UninitializedCollectionException() {
	}

	UninitializedCollectionException(String message) {
		super(message);
	}

	UninitializedCollectionException(String message, Throwable cause) {
		super(message, cause);
	}

	UninitializedCollectionException(Throwable cause) {
		super(cause);
	}
}
