package works.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonDocument;
import org.bson.BsonInt64;

import static java.util.Objects.requireNonNull;

/**
 * De-interlaces change stream events associated with different transactions.
 */
class Demultiplexer {
	private final Map<TransactionID, List<ChangeStreamDocument<BsonDocument>>> transactionsInProgress = new ConcurrentHashMap<>();

	public void add(ChangeStreamDocument<BsonDocument> event) {
		TransactionID key = TransactionID.from(requireNonNull(event));
		transactionsInProgress
			.computeIfAbsent(key, __ -> new ArrayList<>())
			.add(event);
	}

	public List<ChangeStreamDocument<BsonDocument>> pop(ChangeStreamDocument<BsonDocument> finalEvent) {
		return transactionsInProgress.remove(TransactionID.from(finalEvent));
	}

	private record TransactionID(BsonDocument lsid, BsonInt64 txnNumber) {
		private TransactionID {
			requireNonNull(lsid);
			requireNonNull(txnNumber);
		}

		public static TransactionID from(ChangeStreamDocument<?> event) {
			return new TransactionID(event.getLsid(), event.getTxnNumber());
		}
	}
}
