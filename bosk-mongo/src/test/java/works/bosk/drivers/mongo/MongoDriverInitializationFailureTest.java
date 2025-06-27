package works.bosk.drivers.mongo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.drivers.state.TestEntity;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskTestUtils.boskName;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL;

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
			new Bosk<TestEntity>(boskName("Fail"), TestEntity.class, this::initialRoot, super.createDriverFactory(logController, testInfo), Bosk.simpleRegistrar());
		});
	}

}
