package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.bson.BsonDocument;

class ForwardingChangeListener implements ChangeListener {
	final ChangeListener downstream;

	public ForwardingChangeListener(ChangeListener downstream) {
		this.downstream = downstream;
	}

	@Override
	public void onConnectionSucceeded() throws UnrecognizedFormatException, UninitializedCollectionException, InterruptedException, IOException, InitialRootActionException, TimeoutException, FailedSessionException {
		downstream.onConnectionSucceeded();
	}

	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		downstream.onEvent(event);
	}

	@Override
	public void onConnectionFailed() throws InterruptedException, TimeoutException {
		downstream.onConnectionFailed();
	}

	@Override
	public void onDisconnect(Throwable e) {
		downstream.onDisconnect(e);
	}
}
