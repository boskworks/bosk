package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.OperationType;

/**
 * Indicates that the database's {@link Manifest} now describes a different format
 * than the one this driver loaded, so the collection must be reloaded.
 * <p>
 * This is the expected result of a
 * {@link works.bosk.drivers.mongo.MongoDriver#refurbish refurbish} to a different format.
 * Unlike an {@link UnprocessableEventException}, it is not a sign that anything has gone wrong:
 * the {@link ChangeReceiver} reloads the state and continues.
 * <p>
 * It is deliberately not a subtype of {@link UnprocessableEventException},
 * so that a caller handling unprocessable events must decide explicitly
 * what to do about a format change.
 */
public class FormatChangedException extends Exception {
	FormatChangedException(String message, OperationType operationType) {
		super(message + ": " + operationType.name());
	}

	FormatChangedException(String message, Throwable cause, OperationType operationType) {
		super(message + ": " + operationType.name(), cause);
	}

	FormatChangedException(Throwable cause, OperationType operationType) {
		super(cause);
	}
}
