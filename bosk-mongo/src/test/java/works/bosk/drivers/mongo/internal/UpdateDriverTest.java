package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.AtomicUpdateOperation.Outcome.DONE;
import static works.bosk.drivers.mongo.internal.AtomicUpdateOperation.Outcome.NOTHING_CHANGED;
import static works.bosk.drivers.mongo.internal.AtomicUpdateOperation.Outcome.needsTransaction;
import static works.bosk.drivers.mongo.internal.TransactionalUpdateOperation.Outcome.ABORT;
import static works.bosk.drivers.mongo.internal.TransactionalUpdateOperation.Outcome.COMMIT;

/**
 * Checks the execution half of {@link UpdateOperation}: an atomic operation
 * reports whether it matched a document and may return one transactional
 * fallback, which runs in a transaction on the same session. An update that
 * matches nothing makes the driver ask the format driver whether the format
 * changed.
 * <p>
 * The format driver here is a {@link DisconnectedDriver}, which throws whenever
 * it is asked anything. That makes it a convenient tripwire: a test that
 * expects no exception also demonstrates that the driver did not consult the
 * format driver.
 */
class UpdateDriverTest {
	private static MongoService mongoService;
	private MongoClient client;
	private MongoCollection<BsonDocument> raw;
	private UpdateDriver driver;

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@AfterAll
	static void closeMongoConnection() {
		mongoService.close();
	}

	@BeforeEach
	void setup(TestInfo testInfo) {
		MongoClientSettings settings = mongoService.clientSettings(testInfo);
		client = MongoClients.create(settings);
		raw = client
			.getDatabase(getClass().getSimpleName())
			.getCollection("collection", BsonDocument.class);
		raw.drop();
		SessionCollection collection = SessionCollection.of(raw, client);
		driver = new UpdateDriver(
			new DisconnectedDriver<TestEntity>(new Exception("test")),
			collection,
			1_000,
			reason -> { throw new AssertionError("Unexpected disconnect", reason); });
	}

	@AfterEach
	void assertNoOpenTransactions(TestInfo testInfo) throws InterruptedException {
		try {
			mongoService.assertNoOpenTransactions(MongoService.clientApplicationName(testInfo));
		} finally {
			client.close();
		}
	}

	@Test
	void atomicOperation_completesWithoutTransaction() {
		driver.execute((AtomicUpdateOperation) collection -> {
			collection.insertOne(new BsonDocument("_id", new BsonString("atomic")));
			return DONE;
		});
		assertTrue(documentExists("atomic"));
	}

	@Test
	void atomicOperationChangingNothing_checksFormat() {
		assertThrows(DisconnectedException.class,
			() -> driver.execute((AtomicUpdateOperation) collection -> NOTHING_CHANGED));
	}

	@Test
	void fallbackOperation_runsInTransaction() {
		driver.execute((AtomicUpdateOperation) collection -> needsTransaction(sessionCollection -> {
			sessionCollection.insertOne(new BsonDocument("_id", new BsonString("fallback")));
			return COMMIT;
		}));
		assertTrue(documentExists("fallback"));
	}

	@Test
	void fallbackOperation_abortRollsBack() {
		assertThrows(DisconnectedException.class,
			() -> driver.execute((AtomicUpdateOperation) collection -> needsTransaction(sessionCollection -> {
				sessionCollection.insertOne(new BsonDocument("_id", new BsonString("aborted")));
				return ABORT;
			})));
		assertFalse(documentExists("aborted"));
	}

	@Test
	void atomicOperationStartingTransaction_isRejected() {
		assertThrows(IllegalStateException.class,
			() -> driver.execute((AtomicUpdateOperation) collection -> {
				collection.ensureTransactionStarted();
				return DONE;
			}));
	}

	private boolean documentExists(String id) {
		try (var cursor = raw.find(new BsonDocument("_id", new BsonString(id))).cursor()) {
			return cursor.hasNext();
		}
	}
}
