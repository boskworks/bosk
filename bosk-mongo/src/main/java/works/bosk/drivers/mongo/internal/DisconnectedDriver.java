package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.drivers.mongo.status.MongoStatus;

@RequiredArgsConstructor
final class DisconnectedDriver<R extends StateTreeNode> implements FormatDriver<R> {
	private final Throwable reason;
	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		throw disconnected();
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected();
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		throw disconnected();
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		throw disconnected();
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
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
	public void onRevisionToSkip(BsonInt64 revision) {
		throw new AssertionError("Resynchronization should not tell DisconnectedDriver to skip a revision");
	}

	@Override
	public StateAndMetadata<R> loadAllState() {
		throw disconnected();
	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) {
		throw disconnected();
	}

	private DisconnectedException disconnected() {
		return new DisconnectedException(reason);
	}

}
