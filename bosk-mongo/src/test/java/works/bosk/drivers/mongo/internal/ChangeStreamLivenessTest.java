package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.time.Duration;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static works.bosk.drivers.mongo.internal.MongoService.FailureMode.PARTITION;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;

/**
 * Checks that our change stream does not go quietly dead. A connection that
 * stops delivering must surface an error rather than blocking forever, so the
 * driver can rebuild the stream; otherwise a socket that dies while the
 * database stays reachable would leave the bosk serving stale data indefinitely.
 * <p>
 * The change stream here is opened with the very same settings the driver uses,
 * via {@link ClientSettings}, so this tests our configuration rather than
 * MongoDB's.
 */
@InjectFields
@ReplayLogsOnFailure
public class ChangeStreamLivenessTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(MongoDriverSettings.DatabaseFormat.SEQUOIA),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s
			.timescaleMS(SHORT_TIMESCALE)
		));
	}

	@Test
	@DisruptsMongoProxy
	void deadSocket_surfacesInsteadOfBlockingForever(TestInfo testInfo) throws InterruptedException {
		ClientSettings clients = ClientSettings.derive(mongoService.clientSettings(testInfo), driverSettings);
		try (MongoClient client = MongoClients.create(clients.changeStream())) {
			MongoCollection<BsonDocument> collection = client
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection(), BsonDocument.class);
			try (MongoChangeStreamCursor<ChangeStreamDocument<BsonDocument>> cursor =
				ChangeReceiver.watch(collection, clients.changeStreamMaxAwaitTimeMS()).cursor()
			) {
				long pollInterval = clients.changeStreamMaxAwaitTimeMS();

				// A healthy idle stream answers within each poll interval, so it
				// must not trip the socket read timeout.
				Thread.sleep(3 * pollInterval);
				assertDoesNotThrow(cursor::tryNext, "A healthy idle change stream must not time out");

				// Now make the socket go silent. The database stays reachable,
				// so a freshly opened change stream would work.
				mongoService.disruptConnection(PARTITION);
				tearDownActions.add(() -> mongoService.restoreConnection(PARTITION));

				// The socket read timeout makes the dead stream's read fail, and
				// the driver's resumption then hands the error back once the
				// server monitor has marked the server unknown (within one poll
				// interval) and server selection has failed (within 2 * poll
				// interval). So the wait is a small multiple of the poll interval;
				// without the read timeout — that is, if changeStreamReadTimeout
				// drops back to 0 — next() blocks here forever and this fails.
				assertTimeoutPreemptively(
					Duration.ofMillis(10 * pollInterval),
					() -> assertThrows(MongoException.class, cursor::next),
					"A dead change-stream socket must surface an error, not block forever");
			}
		}
	}
}
