package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoClient;

/**
 * Indicates a call to {@link MongoClient#startSession()} threw an exception,
 * leaving us unable to interact with the database. This is often a transient
 * failure that could be remedied by retrying.
 */
class FailedSessionException extends Exception {
	public FailedSessionException(String message) {
		super(message);
	}

	public FailedSessionException(String message, Throwable cause) {
		super(message, cause);
	}

	public FailedSessionException(Throwable cause) {
		super(cause);
	}
}
