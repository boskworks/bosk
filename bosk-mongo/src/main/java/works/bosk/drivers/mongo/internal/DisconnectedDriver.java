package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.drivers.mongo.status.MongoStatus;

/**
 * A {@link FormatDriver} that is installed in place of the live FormatDriver when the
 * driver is disconnected. Every operation throws {@link DisconnectedException}, so the
 * driver refuses updates and operations waiting to retry keep waiting for a replacement.
 */
@RequiredArgsConstructor
final class DisconnectedDriver<R extends StateTreeNode> implements FormatDriver<R> {
	private final Throwable reason;
	@Override
	public DatabaseFormat format() {
		throw disconnected();
	}

	@Override
	public Identifier formatId() {
		throw disconnected();
	}

	@Override
	public <T> UpdateOperation submitReplacement(Reference<T> target, T newValue) {
		throw disconnected();
	}

	@Override
	public <T> UpdateOperation submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected();
	}

	@Override
	public <T> UpdateOperation submitConditionalCreation(Reference<T> target, T newValue) {
		throw disconnected();
	}

	@Override
	public <T> UpdateOperation submitDeletion(Reference<T> target) {
		throw disconnected();
	}

	@Override
	public <T> UpdateOperation submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected();
	}

	@Override
	public void requireFormatUnchanged() {
		throw disconnected();
	}

	@Override
	public void flush() {
		throw disconnected();
	}

	@Override
	public MongoStatus readStatus() {
		return new MongoStatus(
			"Disconnected: " + reason,
			null,
			null
		);
	}

	@Override
	public void close() {
		// Nothing to do
	}

	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) {
		throw disconnected();
	}

	@Override
	public StateAndMetadata<R> loadAllState() {
		throw disconnected();
	}

	@Override
	public void writeAllState(StateAndMetadata<R> priorContents) {
		throw disconnected();
	}

	@Override
	public BsonDocument rootDocumentsFilter() {
		throw disconnected();
	}

	@Override
	public void onHasBeenApplied(StateAndMetadata<R> stateAndMetadata) {
		throw disconnected();
	}

	/**
	 * Chooses how the receiver should react to this disconnection.
	 * <p>
	 * A stale view of a healthy database is worth reconnecting to immediately:
	 * <ul>
	 * <li>{@link RevisionDisruptedException} can be a symptom of a
	 * {@link MainDriver#refurbish() refurbish} to another format, leaving the
	 * driver looking at documents that no longer exist.</li>
	 * <li>{@link FormatChangedDuringUpdateException} means an update lost a race
	 * with exactly that refurbish.</li>
	 * </ul>
	 * Neither can be triggered by the reconnect itself: it reloads the database
	 * and builds a driver for whatever format it finds, so it never performs the
	 * updates that raise these exceptions.
	 * <p>
	 * Every other reason might be a database-health problem, so the receiver waits
	 * for its next poll before reconnecting.
	 */
	private DisconnectedException disconnected() {
		return switch (reason) {
			case RevisionDisruptedException e -> new ImmediateReconnectException(e);
			case FormatChangedDuringUpdateException e -> new ImmediateReconnectException(e);
			default -> new DisconnectedException(reason);
		};
	}

}
