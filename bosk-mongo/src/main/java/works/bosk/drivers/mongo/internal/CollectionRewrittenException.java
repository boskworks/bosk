package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.OperationType;

/**
 * Indicates that the collection has been rewritten in a way that makes this
 * driver's loaded state obsolete, so it must be reloaded. A refurbish does this
 * by deleting and rewriting the root state document, and it may also change the
 * {@link Manifest}'s format.
 * <p>
 * This is the expected result of a
 * {@link works.bosk.drivers.mongo.MongoDriver#refurbish refurbish}.
 * Unlike an {@link UnprocessableEventException}, it is not a sign that anything has gone wrong:
 * the {@link ChangeReceiver} reloads the state and continues.
 * <p>
 * It is deliberately not a subtype of {@link UnprocessableEventException},
 * so that a caller handling unprocessable events must decide explicitly
 * what to do about a collection rewrite.
 */
public class CollectionRewrittenException extends Exception {
	CollectionRewrittenException(String message, OperationType operationType) {
		super(message + ": " + operationType.name());
	}

	CollectionRewrittenException(String message, Throwable cause, OperationType operationType) {
		super(message + ": " + operationType.name(), cause);
	}

	CollectionRewrittenException(Throwable cause, OperationType operationType) {
		super(cause);
	}
}
