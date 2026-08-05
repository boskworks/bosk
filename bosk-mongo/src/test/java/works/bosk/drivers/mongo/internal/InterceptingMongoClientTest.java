package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.bosk.libtesting.BlockingGate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptingMongoClientTest {
	private static MongoService mongoService;
	private static final String DB_NAME = "InterceptingMongoClientTest";
	private static final String COLLECTION_NAME = "testCollection";

	@BeforeAll
	static void setupMongo() {
		mongoService = new MongoService();
	}

	@Test
	void blockCollectionMethod_blocksMatchingCall_untilReleased() throws Exception {
		InterceptingMongoClient interceptor = InterceptingMongoClient.wrapping(mongoService.client());
		BlockingGate gate = new BlockingGate("the matching deleteMany");
		interceptor.blockCollectionMethod("deleteMany", filter -> filterHasId(filter, "doc1"), gate);

		AtomicBoolean deleted = new AtomicBoolean(false);
		Thread deleter = new Thread(() -> {
			collection(interceptor.client()).deleteMany(new BsonDocument("_id", new BsonString("doc1")));
			deleted.set(true);
		});
		deleter.start();
		gate.awaitSignal(Duration.ofSeconds(5));
		assertFalse(deleted.get(), "The matching deleteMany should be blocked until released");
		gate.release();
		deleter.join(5_000);
		assertFalse(deleter.isAlive(), "The deleteMany should have completed after release");
		assertTrue(deleted.get(), "The deleteMany should have been delegated after release");
	}

	@Test
	void blockCollectionMethod_doesNotBlockNonMatchingCall() {
		InterceptingMongoClient interceptor = InterceptingMongoClient.wrapping(mongoService.client());
		BlockingGate gate = new BlockingGate("the non-matching deleteMany");
		interceptor.blockCollectionMethod("deleteMany", filter -> filterHasId(filter, "doc1"), gate);

		assertDoesNotThrow(() ->
			collection(interceptor.client()).deleteMany(new BsonDocument("_id", new BsonString("doc2"))),
			"A non-matching deleteMany should pass straight through");
	}

	@Test
	void blockCollectionMethod_blocksThroughChainedCollections() throws Exception {
		InterceptingMongoClient interceptor = InterceptingMongoClient.wrapping(mongoService.client());
		BlockingGate gate = new BlockingGate("the deleteMany on a chained collection");
		interceptor.blockCollectionMethod("deleteMany", filter -> filterHasId(filter, "doc1"), gate);

		AtomicBoolean deleted = new AtomicBoolean(false);
		Thread deleter = new Thread(() -> {
			collection(interceptor.client())
				.withReadConcern(ReadConcern.MAJORITY)
				.deleteMany(new BsonDocument("_id", new BsonString("doc1")));
			deleted.set(true);
		});
		deleter.start();
		gate.awaitSignal(Duration.ofSeconds(5));
		assertFalse(deleted.get(), "The deleteMany should be blocked even on a chained collection");
		gate.release();
		deleter.join(5_000);
		assertFalse(deleter.isAlive(), "The deleteMany should have completed after release");
	}

	@Test
	void pauseFindCursor_pausesAfterDocuments_untilReleased() throws Exception {
		insert("doc1");
		insert("doc2");
		InterceptingMongoClient interceptor = InterceptingMongoClient.wrapping(mongoService.client());
		BlockingGate gate = new BlockingGate("the find's cursor");
		interceptor.pauseFindCursor(filter -> true, 1, gate);

		AtomicInteger documentsRead = new AtomicInteger();
		Thread reader = new Thread(() -> {
			try (var cursor = collection(interceptor.client()).find(new BsonDocument()).cursor()) {
				while (cursor.hasNext()) {
					cursor.next();
					documentsRead.incrementAndGet();
				}
			}
		});
		reader.start();
		gate.awaitSignal(Duration.ofSeconds(5));
		assertTrue(reader.isAlive(), "The cursor should be paused before delivering the first document");
		gate.release();
		reader.join(5_000);
		assertFalse(reader.isAlive(), "The reader should have completed after release");
		assertEquals(2, documentsRead.get(), "All documents should have been read");
	}

	@Test
	void pauseFindCursor_doesNotPauseNonMatchingFinds() {
		insert("doc1");
		InterceptingMongoClient interceptor = InterceptingMongoClient.wrapping(mongoService.client());
		BlockingGate gate = new BlockingGate("the non-matching find");
		interceptor.pauseFindCursor(filter -> false, 1, gate);

		assertDoesNotThrow(() -> {
			try (var cursor = collection(interceptor.client()).find(new BsonDocument("_id", new BsonString("doc1"))).cursor()) {
				assertTrue(cursor.hasNext());
				cursor.next();
			}
		}, "A non-matching find should read through without pausing");
	}

	private void insert(String id) {
		collection(mongoService.client()).insertOne(new BsonDocument("_id", new BsonString(id)));
	}

	private MongoCollection<BsonDocument> collection(MongoClient client) {
		return client.getDatabase(DB_NAME).getCollection(COLLECTION_NAME, BsonDocument.class);
	}

	private static boolean filterHasId(Bson filter, String id) {
		if (filter == null) {
			return false;
		}
		BsonDocument asDoc = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		BsonValue idValue = asDoc.get("_id");
		return idValue instanceof BsonString string && string.getValue().equals(id);
	}
}
