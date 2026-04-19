package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoClient;

/**
 * Indicates a call to {@link MongoClient#startSession()} threw an exception,
 * leaving us unable to interact with the database. This is often a transient
 * failure that could be remedied by retrying.
 */
public class FailedMongoClientSessionException extends Exception {
	FailedMongoClientSessionException(String message) {
		super(message);
	}

	FailedMongoClientSessionException(String message, Throwable cause) {
		super(message, cause);
	}

	FailedMongoClientSessionException(Throwable cause) {
		super(cause);
	}
}
