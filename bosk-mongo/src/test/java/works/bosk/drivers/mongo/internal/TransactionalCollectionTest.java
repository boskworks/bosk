package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.internal.TransactionalCollection.Session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionalCollectionTest {
	private static MongoService mongoService;
	private static final String DB_NAME = "TransactionalCollectionTest";
	private static final String COLLECTION_NAME = "testCollection";
	private TransactionalCollection collection;

	@BeforeAll
	static void setupMongo() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setupCollection() {
		MongoClient client = mongoService.client();
		MongoCollection<BsonDocument> raw = client
			.getDatabase(DB_NAME)
			.getCollection(COLLECTION_NAME, BsonDocument.class);
		raw.drop();
		collection = TransactionalCollection.of(raw, client);
	}

	@Test
	void findLatest_worksWithoutSession() throws FailedMongoClientSessionException {
		makeData("test");
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("test")))
			.cursor()
		) {
			assertTrue(cursor.hasNext());
			assertEquals("test", cursor.next().getString("_id").getValue());
		}
	}

	@Test
	void findLatest_returnsData() throws FailedMongoClientSessionException {
		makeData("doc1");
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("doc1")))
			.cursor()
		) {
			assertTrue(cursor.hasNext());
		}
	}

	@Test
	void newSession_createsSession() throws FailedMongoClientSessionException {
		try (Session session = collection.newSession()) {
			assertNotNull(session.clientSession);
			assertFalse(session.isReadOnly);
		}
	}

	@Test
	void newReadOnlySession_createsSession() throws FailedMongoClientSessionException {
		try (Session session = collection.newReadOnlySession()) {
			assertNotNull(session.clientSession);
			assertTrue(session.isReadOnly);
		}
	}

	@Test
	void readOnlySession_rejectsWrites() throws FailedMongoClientSessionException {
		try (Session _ = collection.newReadOnlySession()) {
			BsonDocument filter = new BsonDocument("_id", new BsonString("doc1"));
			BsonDocument update = new BsonDocument("$set", new BsonDocument("x", new BsonInt32(1)));
			assertThrows(IllegalStateException.class,
				() -> collection.insertOne(new BsonDocument("_id", new BsonString("new"))));
			assertThrows(IllegalStateException.class, () -> collection.updateOne(filter, update));
			assertThrows(IllegalStateException.class, () -> collection.updateOne(filter, update, new UpdateOptions()));
			assertThrows(IllegalStateException.class, () -> collection.deleteOne(filter));
			assertThrows(IllegalStateException.class, () -> collection.deleteMany(filter));
			assertThrows(IllegalStateException.class,
				() -> collection.replaceOne(filter, new BsonDocument("_id", new BsonString("doc1")), new ReplaceOptions()));
		}
	}

	@Test
	void nestedSession_throws() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			assertThrows(IllegalStateException.class, () -> collection.newSession());
		}
	}

	@Test
	void nestedReadOnlySession_throws() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			assertThrows(IllegalStateException.class, () -> collection.newReadOnlySession());
		}
	}

	@Test
	void sessionCreation_afterClose_allowed() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) { }
		assertDoesNotThrow(() -> {
			try (Session _ = collection.newSession()) { }
		});
	}

	@Test
	void find_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.find(new BsonDocument("_id", new BsonString("x"))));
	}

	@Test
	void countDocuments_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.countDocuments(new BsonDocument(), new CountOptions()));
	}

	@Test
	void insertOne_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.insertOne(new BsonDocument("_id", new BsonString("x"))));
	}

	@Test
	void deleteOne_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.deleteOne(new BsonDocument("_id", new BsonString("x"))));
	}

	@Test
	void deleteMany_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.deleteMany(new BsonDocument()));
	}

	@Test
	void replaceOne_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.replaceOne(
				new BsonDocument("_id", new BsonString("x")),
				new BsonDocument("_id", new BsonString("x")),
				new ReplaceOptions()));
	}

	@Test
	void updateOne_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.updateOne(
				new BsonDocument("_id", new BsonString("x")),
				new BsonDocument("$set", new BsonDocument("val", new BsonInt32(1)))));
	}

	@Test
	void updateOneWithOptions_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.updateOne(
				new BsonDocument("_id", new BsonString("x")),
				new BsonDocument("$set", new BsonDocument("val", new BsonInt32(1))),
				new UpdateOptions()));
	}

	@Test
	void ensureTransactionStarted_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.ensureTransactionStarted());
	}

	@Test
	void abortTransaction_withoutSession_throws() {
		assertThrows(IllegalStateException.class,
			() -> collection.abortTransaction());
	}

	@Test
	void insertAndFindLatest_withSession_works() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("sdoc")));
			collection.commitTransactionIfAny();
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("sdoc"))).cursor()
		) {
			assertTrue(cursor.hasNext());
		}
	}

	@Test
	void deleteOne_withSession_works() throws FailedMongoClientSessionException {
		makeData("delme");
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			DeleteResult result = collection.deleteOne(new BsonDocument("_id", new BsonString("delme")));
			assertEquals(1, result.getDeletedCount());
			collection.commitTransactionIfAny();
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("delme"))).cursor()
		) {
			assertFalse(cursor.hasNext());
		}
	}

	@Test
	void deleteMany_withSession_works() throws FailedMongoClientSessionException {
		makeData("dm1");
		makeData("dm2");
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			DeleteResult result = collection.deleteMany(new BsonDocument("_id", new BsonString("dm1")));
			assertEquals(1, result.getDeletedCount());
			collection.commitTransactionIfAny();
		}
	}

	@Test
	void replaceOne_withSession_works() throws FailedMongoClientSessionException {
		makeData("rpl");
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			BsonDocument replacement = new BsonDocument("_id", new BsonString("rpl"))
				.append("val", new BsonInt32(42));
			UpdateResult result = collection.replaceOne(
				new BsonDocument("_id", new BsonString("rpl")),
				replacement,
				new ReplaceOptions().upsert(true));
			assertEquals(1, result.getMatchedCount());
			collection.commitTransactionIfAny();
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("rpl"))).cursor()
		) {
			assertTrue(cursor.hasNext());
			assertEquals(42, cursor.next().getInt32("val").getValue());
		}
	}

	@Test
	void updateOne_withSession_works() throws FailedMongoClientSessionException {
		makeData("upd");
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			UpdateResult result = collection.updateOne(
				new BsonDocument("_id", new BsonString("upd")),
				new BsonDocument("$set", new BsonDocument("val", new BsonInt32(99))));
			assertEquals(1, result.getMatchedCount());
			collection.commitTransactionIfAny();
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("upd"))).cursor()
		) {
			assertTrue(cursor.hasNext());
			assertEquals(99, cursor.next().getInt32("val").getValue());
		}
	}

	@Test
	void updateOneWithOptions_withSession_works() throws FailedMongoClientSessionException {
		makeData("updo");
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			UpdateResult result = collection.updateOne(
				new BsonDocument("_id", new BsonString("updo")),
				new BsonDocument("$set", new BsonDocument("val", new BsonInt32(88))),
				new UpdateOptions().upsert(false));
			assertEquals(1, result.getMatchedCount());
			collection.commitTransactionIfAny();
		}
	}

	@Test
	void countDocuments_withSession_works() throws FailedMongoClientSessionException {
		makeData("cd1");
		makeData("cd2");
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			long count = collection.countDocuments(new BsonDocument(), new CountOptions());
			assertEquals(2, count);
			collection.commitTransactionIfAny();
		}
	}

	@Test
	void commit_persistsData() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("commitDoc")));
			collection.commitTransactionIfAny();
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("commitDoc"))).cursor()
		) {
			assertTrue(cursor.hasNext(), "Committed data must be visible");
		}
	}

	@Test
	void abortOnClose_rollsBackData() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("rollbackDoc")));
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("rollbackDoc"))).cursor()
		) {
			assertFalse(cursor.hasNext(), "Uncommitted data must not be visible after abort");
		}
	}

	@Test
	void explicitAbort_rollsBackData() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("abortDoc")));
			collection.abortTransaction();
		}
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("abortDoc"))).cursor()
		) {
			assertFalse(cursor.hasNext(), "Aborted data must not be visible");
		}
	}

	@Test
	void readOnlySession_canStartTransaction() throws FailedMongoClientSessionException {
		try (Session _ = collection.newReadOnlySession()) {
			assertDoesNotThrow(() -> collection.ensureTransactionStarted());
		}
	}

	@Test
	void ensureTransactionStarted_twice_isIdempotent() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.ensureTransactionStarted();
			collection.commitTransactionIfAny();
		}
	}

	@Test
	void findLatestWithSession_seesTransactionData() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("txDoc")));
			try (MongoCursor<BsonDocument> cursor = collection
				.find(new BsonDocument("_id", new BsonString("txDoc")))
				.cursor()
			) {
				assertTrue(cursor.hasNext(), "Transaction should see its own writes");
			}
			collection.abortTransaction();
		}
	}

	@Test
	void standaloneFindLatest_doesNotSeeUncommittedData() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("uncommittedDoc")));
			try (MongoCursor<BsonDocument> cursor = collection
				.findLatest(new BsonDocument("_id", new BsonString("uncommittedDoc")))
				.cursor()
			) {
				assertFalse(cursor.hasNext(), "Standalone reads must not see uncommitted transaction data");
			}
			collection.abortTransaction();
		}
	}

	@Test
	void multipleInsertsAndFindsWithinSession() throws FailedMongoClientSessionException {
		try (Session _ = collection.newSession()) {
			collection.ensureTransactionStarted();
			collection.insertOne(new BsonDocument("_id", new BsonString("a")));
			collection.insertOne(new BsonDocument("_id", new BsonString("b")));
			collection.insertOne(new BsonDocument("_id", new BsonString("c")));
			long count = collection.countDocuments(new BsonDocument(), new CountOptions());
			assertEquals(3, count);
			collection.abortTransaction();
		}
	}

	@Test
	void findAfterSessionClose_throws() throws FailedMongoClientSessionException {
		{
			try (Session _ = collection.newSession()) { }
		}
		assertThrows(IllegalStateException.class,
			() -> collection.find(new BsonDocument("_id", new BsonString("x"))));
	}

	@Test
	void sessionNames_areDistinct() throws FailedMongoClientSessionException {
		String name1, name2;
		try (Session s = collection.newSession()) {
			name1 = s.name;
		}
		try (Session s = collection.newSession()) {
			name2 = s.name;
		}
		assertNotNull(name1);
		assertNotNull(name2);
		assertNotEquals(name1, name2, "Session names should be distinct");
	}

	@Test
	void readOnlySessionNames_prefixedWithR() throws FailedMongoClientSessionException {
		try (Session s = collection.newReadOnlySession()) {
			assertTrue(s.name.startsWith("r"));
		}
	}

	@Test
	void sessionNames_prefixedWithS() throws FailedMongoClientSessionException {
		try (Session s = collection.newSession()) {
			assertTrue(s.name.startsWith("s"));
		}
	}

	@Test
	void concurrentSessions_areIsolated() throws FailedMongoClientSessionException {
		MongoClient client = mongoService.client();
		MongoCollection<BsonDocument> raw = client
			.getDatabase(DB_NAME)
			.getCollection(COLLECTION_NAME, BsonDocument.class);
		TransactionalCollection collection2 = TransactionalCollection.of(raw, client);

		try (Session _ = collection.newSession();
			Session _ = collection2.newSession()
		) {
			collection.ensureTransactionStarted();
			collection2.ensureTransactionStarted();

			collection.insertOne(new BsonDocument("_id", new BsonString("s1Doc")));

			try (MongoCursor<BsonDocument> cursor = collection
				.find(new BsonDocument("_id", new BsonString("s1Doc"))).cursor()
			) {
				assertTrue(cursor.hasNext(), "Session should see its own write");
			}

			try (MongoCursor<BsonDocument> cursor = collection2
				.find(new BsonDocument("_id", new BsonString("s1Doc"))).cursor()
			) {
				assertFalse(cursor.hasNext(), "Session must not see other session's uncommitted write");
			}

			collection.commitTransactionIfAny();

			try (MongoCursor<BsonDocument> cursor = collection2
				.find(new BsonDocument("_id", new BsonString("s1Doc"))).cursor()
			) {
				assertFalse(cursor.hasNext(), "Snapshot isolation: still should not see s1Doc after s1 committed");
			}

			collection2.commitTransactionIfAny();
		}

		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(new BsonDocument("_id", new BsonString("s1Doc"))).cursor()
		) {
			assertTrue(cursor.hasNext(), "s1Doc should be visible after s1 commit");
		}
		try (MongoCursor<BsonDocument> cursor = collection2
			.findLatest(new BsonDocument("_id", new BsonString("s1Doc"))).cursor()
		) {
			assertTrue(cursor.hasNext(), "s1Doc should be visible to collection2 after s1 commit");
		}
	}

	private static void insertFresh(TransactionalCollection coll, BsonDocument doc) throws FailedMongoClientSessionException {
		try (Session _ = coll.newSession()) {
			coll.ensureTransactionStarted();
			coll.insertOne(doc);
			coll.commitTransactionIfAny();
		}
	}

	private void makeData(String id) throws FailedMongoClientSessionException {
		insertFresh(collection, new BsonDocument("_id", new BsonString(id)));
	}

}
