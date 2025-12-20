package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.bson.BsonDocument;

/**
 * Used by {@link ChangeReceiver} to delegate handling of events to other components.
 * <p>
 * Meeting the ordering guarantees of {@link works.bosk.BoskDriver BoskDriver}
 * is simplified tremendously if all change events are processed by the same thread,
 * and we use the {@link ChangeReceiver} thread for that purpose,
 * yet we don't want to cram all the event handling logic
 * into {@code ChangeReceiver} itself.
 */
interface ChangeListener {
	void onConnectionSucceeded() throws
		UnrecognizedFormatException,
		UninitializedCollectionException,
		InterruptedException,
		IOException,
		InitialRootActionException,
		TimeoutException, FailedSessionException;

	/**
	 * @param event is a document-specific event, with a non-null {@link ChangeStreamDocument#getDocumentKey() document key}.
	 */
	void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException;

	void onConnectionFailed() throws InterruptedException, TimeoutException;
	void onDisconnect(Throwable e);
}
