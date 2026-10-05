package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.bson.BsonDocument;
import works.bosk.BoskDriver;

/**
 * Used by {@link ChangeReceiver} to delegate handling of events to other components.
 * <p>
 * Meeting the ordering guarantees of {@link works.bosk.BoskDriver BoskDriver}
 * is simplified tremendously if all change events are processed by the same thread,
 * and we use the {@code ChangeReceiver} thread for that purpose,
 * yet we don't want to cram all the event handling logic
 * into {@code ChangeReceiver} itself.
 */
interface ChangeListener {

	/**
	 * Called when the change stream connection to MongoDB has been established.
	 * Throws a rich variety of exceptions that the {@link ChangeReceiver} is prepared to handle.
	 */
	void onConnectionSucceeded() throws
		FailedMongoClientSessionException,
		InitialStateException,
		InterruptedException,
		InvalidCollectionContentsException,
		IOException,
		TimeoutException,
		UnrecognizedFormatException;

	/**
	 * @param event is a document-specific event, with a non-null {@link ChangeStreamDocument#getDocumentKey() document key}.
	 */
	void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException;

	/**
	 * @throws DownstreamInitialStateException if {@link BoskDriver#initialState}
	 * is still underway but the downstream driver fails to provide an initial state.
	 */
	void onConnectionFailed(Exception cause) throws DownstreamInitialStateException;

	void onDisconnect(Throwable e);
}
