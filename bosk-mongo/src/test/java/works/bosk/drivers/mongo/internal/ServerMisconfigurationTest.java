package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverStack;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.exceptions.InitialCursorCommandException;
import works.bosk.drivers.mongo.exceptions.InitialCursorTimeoutException;
import works.bosk.logback.BoskLogFilter;
import works.bosk.testing.drivers.state.TestEntity;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST;
import static works.bosk.drivers.mongo.internal.MongoService.newPlainMongoContainer;

/**
 * Separated out from other tests because this involves booting a custom
 * MongoDB container with various kinds of damage.
 */
public class ServerMisconfigurationTest {

	@Test
	void unreachable() throws IOException {
		int port;
		try (var socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		MongoClientSettings clientSettings = MongoService.mongoClientSettings(
			new ServerAddress("localhost", port)
		);
		assertThrows(InitialCursorTimeoutException.class, () -> createBosk(clientSettings));
	}

	/**
	 * This is an important case: it doesn't fail "naturally" because
	 * a standalone MongoDB server will accept connections and return the initial state,
	 * but will then fail when attempting to open a change stream.
	 * Hence, special effort is required to detect this case and pass this test.
	 */
	@Test
	void notAReplicaSet() {
		try (var mongo = newPlainMongoContainer()) {
			mongo.start();
			MongoClientSettings clientSettings = MongoService.mongoClientSettings(
				new ServerAddress(
					mongo.getHost(),
					mongo.getFirstMappedPort()
				)
			);
			var e = assertThrows(InitialCursorCommandException.class, () -> createBosk(clientSettings));
			assertTrue(e.getMessage().contains("replica set"),
				"Error message must mention replica set: [" + e.getMessage() + "]");
		}
	}

	private Bosk<TestEntity> createBosk(MongoClientSettings clientSettings) {
		// We're expecting some timeout warnings from the Mongo driver;
		BoskLogFilter.LogController logController = new BoskLogFilter.LogController();
		logController.setLogging(ERROR, "com.mongodb");

		return new Bosk<>(
			ServerMisconfigurationTest.class.getSimpleName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialRootWithEmptyCatalog,
			BoskConfig.<TestEntity>builder()
				.driverFactory(DriverStack.of(
					BoskLogFilter.withController(logController),
					MongoDriver.factory(
						clientSettings,
						MongoDriverSettings.builder()
							.database("bosk_" + getClass().getSimpleName())
							.timescaleMS(100)
							.initialDatabaseUnavailableMode(FAIL_FAST)
							.build(),
						new BsonSerializer()
					)
				))
				.build()
		);
	}
}
