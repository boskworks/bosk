package works.bosk.drivers.mongo.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.exceptions.InitialRootFailureException;
import works.bosk.testing.drivers.state.TestEntity;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the functionality of {@link MongoDriverSettings.InitialDatabaseUnavailableMode#FAIL FAIL} mode.
 * The other tests in {@link MongoDriverRecoveryTest} exercise {@link MongoDriverSettings.InitialDatabaseUnavailableMode#DISCONNECT DISCONNECT} mode.
 */
public class MongoDriverInitializationFailureTest extends AbstractMongoDriverTest {
	public MongoDriverInitializationFailureTest() {
		super(MongoDriverSettings.builder()
			.database(MongoDriverInitializationFailureTest.class.getSimpleName())
			.initialDatabaseUnavailableMode(FAIL));
	}

	@Test
	@DisruptsMongoProxy
	void initialOutage_throws(TestInfo testInfo) {
		logController.setLogging(ERROR, ChangeReceiver.class);
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());
		assertThrows(InitialRootFailureException.class, ()->{
			new Bosk<>(
				boskName("Fail"),
				TestEntity.class,
				this::initialRoot,
				BoskConfig.<TestEntity>builder()
					.driverFactory(super.createDriverFactory(logController, testInfo))
					.build()
			);
		});
	}

}
