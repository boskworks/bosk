package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import org.bson.BsonDocument;
import works.bosk.libtesting.BlockingGate;

/**
 * A {@link DocCursor} that pauses a read after it has returned a chosen number
 * of documents, so a test can perform a concurrent change while the read is
 * in flight.
 * <p>
 * After the {@code pauseAfter}-th document is read, the cursor signals
 * {@code gate} and then blocks until the test calls {@link BlockingGate#release()}.
 */
final class PausingCursor implements DocCursor {
	private final DocCursor downstream;
	private final int pauseAfter;
	private final BlockingGate gate;
	private int documentsRead = 0;

	PausingCursor(DocCursor downstream, int pauseAfter, BlockingGate gate) {
		this.downstream = downstream;
		this.pauseAfter = pauseAfter;
		this.gate = gate;
	}

	@Override
	public boolean hasNext() {
		return downstream.hasNext();
	}

	@Override
	public BsonDocument next() {
		BsonDocument doc = downstream.next();
		if (++documentsRead == pauseAfter) {
			gate.signal();
			gate.awaitRelease(RELEASE_TIMEOUT);
		}
		return doc;
	}

	@Override
	public void close() {
		downstream.close();
	}

	private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(60);
}
