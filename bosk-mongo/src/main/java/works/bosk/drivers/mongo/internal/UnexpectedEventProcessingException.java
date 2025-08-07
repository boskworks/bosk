package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;

/**
 * Indicates that a {@link RuntimeException} was thrown by
 * {@link ChangeListener#onEvent(ChangeStreamDocument)}.
 *
 * @see UnprocessableEventException
 */
class UnexpectedEventProcessingException extends Exception {
	UnexpectedEventProcessingException(Throwable cause) {
		super(cause);
	}

	UnexpectedEventProcessingException(String message, Throwable cause) {
		super(message, cause);
	}
}
