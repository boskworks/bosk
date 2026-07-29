package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import org.bson.BsonDocument;
import works.bosk.BoskContext;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriver;

/**
 * Additional {@link MongoDriver} functionality that the format-specific drivers must implement.
 * <p>
 * Implementations of this are responsible for the following:
 * <ol><li>
 *     Serializing and deserializing the database contents
 * </li><li>
 *     Processing change stream events via {@link #onEvent}
 * </li><li>
 *     Implementing {@link #flush()} (consider using {@link FlushLock})
 * </li><li>
 *     Propagating {@link BoskContext context} downstream.
 * </li></ol>
 *
 * Implementations are not responsible for:
 * <ol><li>
 *     Handling exceptions from {@link MongoClient} or {@link MongoChangeStreamCursor}
 * </li><li>
 *     Determining the database format
 * </li><li>
 *     Implementing {@link #initialState} or {@link #refurbish()}
 * </li></ol>
 */
sealed public interface FormatDriver<R extends StateTreeNode>
	extends MongoDriver
	permits AbstractFormatDriver, DisconnectedDriver {
	void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException;

	/**
	 * Reads all state documents in the entire collection and, as a side effect,
	 * updates the driver's internal state in the expectation that the loaded state
	 * is to be considered the "current" state with respect to subsequent change stream events.
	 * <p>
	 * This method should be called from the {@link ChangeReceiver} thread so that
	 * the ordering with respect to change stream events is well-defined.
	 * Otherwise, use the receiver's monitor to ensure the appropriate logic
	 * occurs atomically with respect to change stream event processing.
	 * <p>
	 * This method can assume the manifest exists and indicates the appropriate format;
	 * manifest creation/validation is handled elsewhere, and if this assumption is violated for whatever
	 * reason, {@code InvalidCollectionContentsException} is an acceptable exception to throw.
	 * <p>
	 * <em>Maintenance note:</em> This method's contract is pretty weird,
	 * especially with its side effect. It's a struggle to make a clean separation
	 * between the format driver and the main driver, and this particular compromise
	 * was a consequence of encapsulating the revision number logic inside the format driver.
	 * Hopefully some day we can arrive at a cleaner interface.
	 * In the meantime, we've tried to hint at the significance by
	 * calling this operation "load" instead of just "read".
	 *
	 * @throws InvalidCollectionContentsException if the collection contents don't match the declared format
	 */
	StateAndMetadata<R> loadAllState() throws IOException, InvalidCollectionContentsException;

	/**
	 * Initializes the collection to the given state.
	 * <p>
	 * Like {@link #loadAllState}, this also has the side effect of establishing
	 * the state that the driver "knows about".
	 * <p>
	 * Can assume that the collection is empty or nonexistent,
	 * in the sense that there is no mess to clean up,
	 * but should tolerate documents already existing,
	 * by using upsert or replace operations, for example.
	 * @param priorContents the desired state, with metadata representing a (possibly hypothetical)
	 * "prior" state of the database; in particular, the revision number should be incremented
	 * so that a {@link #flush} after a {@link #refurbish} succeeds in waiting for the new state.
	 */
	void initializeCollection(StateAndMetadata<R> priorContents);

	/**
	 * @return a query filter that returns documents corresponding to the roots of the state tree.
	 * Returns a single document.
	 */
	BsonDocument rootDocumentsFilter();

	/**
	 * Indicates that the given state has been {@link #flush() flushed} to the downstream driver already,
	 * or are otherwise known to have been applied to the bosk state.
	 */
	void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata);

	@Override
	default <RR extends StateTreeNode> RR initialState(Class<RR> rootType) {
		throw new UnsupportedOperationException(
			"FormatDriver doesn't need to implement initialState: MainDriver derives it from loadAllState");
	}

	@Override
	default void refurbish() {
		throw new UnsupportedOperationException(
			"FormatDriver doesn't need to implement refurbish: MainDriver derives it from loadAllState and initializeCollection");
	}
}
