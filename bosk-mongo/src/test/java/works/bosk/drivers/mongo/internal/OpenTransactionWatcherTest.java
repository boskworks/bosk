package works.bosk.drivers.mongo.internal;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows that {@link MongoService#assertNoOpenTransactions} can see a transaction
 * that a client left open.
 * <p>
 * That check runs after every test that uses {@link AbstractMongoDriverTest} or
 * {@link MongoDriverConformanceTest}. Its value is that a leaked transaction is
 * reported against the test that leaked it, rather than turning into a hang when
 * a later test tries to drop the collection.
 */
class OpenTransactionWatcherTest {
	private static final String DATABASE = OpenTransactionWatcherTest.class.getSimpleName();
	private static final String COLLECTION = "collection";

	private static MongoService mongoService;

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@AfterAll
	static void closeMongoConnection() {
		mongoService.close();
	}

	@Test
	void noOpenTransaction_passes(TestInfo testInfo) throws InterruptedException {
		assertDoesNotThrow(() ->
			mongoService.assertNoOpenTransactions(MongoService.clientApplicationName(testInfo)));
	}

	@Test
	void transactionLeftOpen_isReported(TestInfo testInfo) throws InterruptedException {
		String appName = MongoService.clientApplicationName(testInfo);
		try (MongoClient client = MongoClients.create(mongoService.clientSettings(testInfo))) {
			MongoCollection<Document> collection = client.getDatabase(DATABASE).getCollection(COLLECTION);
			// Create the collection outside the transaction so that aborting the
			// transaction doesn't have to undo a collection creation.
			collection.insertOne(new Document("_id", 0));

			try (ClientSession session = client.startSession()) {
				try {
					session.startTransaction();
					collection.insertOne(session, new Document("_id", 1));

					AssertionError failure = assertThrows(AssertionError.class, () ->
						mongoService.assertNoOpenTransactions(appName));

					assertTrue(failure.getMessage().contains("txnNumber"),
						"Failure should identify the transaction; was: " + failure.getMessage());
				} finally {
					if (session.hasActiveTransaction()) {
						session.abortTransaction();
					}
				}
			}
		} finally {
			mongoService.client().getDatabase(DATABASE).drop();
		}
	}
}
