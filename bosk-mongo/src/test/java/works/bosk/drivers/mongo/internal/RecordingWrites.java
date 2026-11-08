package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;

/**
 * Records the {@code _id} of every document the driver writes.
 */
final class RecordingWrites implements WriteInterceptor {
	private final List<String> documentIds = new CopyOnWriteArrayList<>();

	@Override
	public void beforeWrite(Bson filter) {
		BsonDocument filterDocument = filter.toBsonDocument(
			BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		BsonValue id = filterDocument.get("_id");
		if (id instanceof BsonString s) {
			documentIds.add(s.getValue());
		}
	}

	List<String> documentIds() {
		return List.copyOf(documentIds);
	}

	void clear() {
		documentIds.clear();
	}
}
