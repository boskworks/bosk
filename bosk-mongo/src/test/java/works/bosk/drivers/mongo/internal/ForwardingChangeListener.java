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
	public void onConnectionSucceeded() throws UnrecognizedFormatException, InterruptedException, IOException, InitialStateException, TimeoutException, FailedMongoClientSessionException, InvalidCollectionContentsException {
		downstream.onConnectionSucceeded();
	}

	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
		downstream.onEvent(event);
	}

	@Override
	public void onConnectionFailed(Exception cause) throws DownstreamInitialStateException {
		downstream.onConnectionFailed(cause);
	}

	@Override
	public void onDisconnect(Throwable e) {
		downstream.onDisconnect(e);
	}
}
