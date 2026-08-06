package works.bosk.drivers.mongo.internal;

import java.util.Iterator;
import org.bson.BsonDocument;

/**
 * A cursor over {@link BsonDocument}s read from MongoDB, like a
 * {@link com.mongodb.client.MongoCursor} but with an API limited to what the
 * driver needs. Routing all reads through this owned type means the read paths
 * don't depend on Mongo's cursor interfaces directly, and gives the test seam
 * a single place to interpose reads.
 */
interface DocCursor extends Iterator<BsonDocument>, AutoCloseable {
	@Override
	void close();
}
