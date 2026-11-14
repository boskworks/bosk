package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import org.bson.BsonDocument;
import works.bosk.BoskContext;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.status.MongoStatus;

/**
 * The format-specific part of a {@link MongoDriver}.
 * <p>
 * Implementations of this are responsible for the following:
 * <ol><li>
 *     Serializing and deserializing the database contents
 * </li><li>
 *     Processing change stream events via {@link #onEvent}
 * </li><li>
 *     Describing updates as {@link UpdateOperation} objects
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
 *     Creating or committing sessions or transactions; that is
 *     {@link UpdateDriver}'s job
 * </li><li>
 *     Implementing {@link MongoDriver#initialState} or {@link MongoDriver#refurbish()}
 * </li></ol>
 */
sealed public interface FormatDriver<R extends StateTreeNode>
	permits AbstractFormatDriver, DisconnectedDriver {
	void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException;

	/**
	 * Describes how to replace the object referenced by {@code target}.
	 * <p>
	 * The returned operation decides whether the update needs a transaction;
	 * see {@link UpdateOperation}.
	 */
	<T> UpdateOperation submitReplacement(Reference<T> target, T newValue);

	/**
	 * Describes how to replace {@code target} if {@code precondition} has the
	 * given value. See {@link #submitReplacement}.
	 */
	<T> UpdateOperation submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue);

	/**
	 * Describes how to create {@code target} if it does not already exist.
	 * See {@link #submitReplacement}.
	 */
	<T> UpdateOperation submitConditionalCreation(Reference<T> target, T newValue);

	/**
	 * Describes how to delete {@code target}. See {@link #submitReplacement}.
	 */
	<T> UpdateOperation submitDeletion(Reference<T> target);

	/**
	 * Describes how to delete {@code target} if {@code precondition} has the
	 * given value. See {@link #submitReplacement}.
	 */
	<T> UpdateOperation submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue);

	/**
	 * Verifies that the database still has this driver's format.
	 * <p>
	 * {@link UpdateDriver} calls this whenever an update changes nothing.
	 * A write that matched a document proves the format was right, because every
	 * document carries the format id the write required in its filter. A write
	 * that matched nothing proves nothing: it may have had nothing to do, or it
	 * may have missed because the collection was refurbished to another format.
	 * Only this driver can tell those apart, because only it knows the format id
	 * it was using.
	 *
	 * @throws FormatChangedDuringUpdateException if the format has changed
	 */
	void requireFormatUnchanged() throws FormatChangedDuringUpdateException;

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
	 * was a consequence of encapsulating the revision tracking logic inside the format driver.
	 * Hopefully some day we can arrive at a cleaner interface.
	 * In the meantime, we've tried to hint at the significance by
	 * calling this operation "load" instead of just "read".
	 *
	 * @throws InvalidCollectionContentsException if the collection contents don't match the declared format
	 */
	StateAndMetadata<R> loadAllState() throws IOException, InvalidCollectionContentsException;

	/**
	 * Writes the state documents for the given state.
	 * <p>
	 * Like {@link #loadAllState}, this also has the side effect of establishing
	 * the state that the driver "knows about".
	 * <p>
	 * Can assume that the collection is empty or nonexistent,
	 * in the sense that there is no mess to clean up,
	 * but should tolerate documents already existing,
	 * by using upsert or replace operations, for example.
	 * <p>
	 * The manifest is written by {@link MainDriver}, not here.
	 * @param priorContents the desired state, with metadata representing a (possibly hypothetical)
	 * "prior" state of the database; in particular, the epoch should be preserved if it exists,
	 * and the revision numbers should be advanced so that a {@link #flush} after
	 * a {@link MongoDriver#refurbish} succeeds in waiting for the new state.
	 */
	void writeAllState(StateAndMetadata<R> priorContents);

	/**
	 * @return a query filter that returns documents corresponding to the roots of the state tree.
	 * Returns a single document.
	 */
	BsonDocument rootDocumentsFilter();

	/**
	 * @return the format this driver implements, as recorded in the manifest
	 */
	DatabaseFormat format();

	/**
	 * @return the id of the format this driver was built for, as recorded in the
	 * manifest and on every state document; a refurbish that changes the format
	 * gives the replacement driver a different value
	 */
	Identifier formatId();

	/**
	 * Indicates that the given state has been {@link #flush() flushed} to the downstream driver already,
	 * or are otherwise known to have been applied to the bosk state.
	 */
	void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata);

	/**
	 * Waits until all prior updates have been applied to the downstream driver.
	 * See {@link works.bosk.BoskDriver#flush()}.
	 */
	void flush() throws IOException, InterruptedException;

	/**
	 * @see MongoDriver#readStatus()
	 */
	MongoStatus readStatus() throws Exception;

	/**
	 * Frees up resources used by this format driver.
	 */
	void close();
}
