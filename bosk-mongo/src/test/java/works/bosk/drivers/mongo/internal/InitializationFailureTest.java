package works.bosk.drivers.mongo.internal;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.exceptions.InitialStateFailureException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the functionality of {@link MongoDriverSettings.InitialDatabaseUnavailableMode#FAIL_FAST FAIL_FAST} mode.
 * The other tests in {@link RecoveryTest} exercise {@link MongoDriverSettings.InitialDatabaseUnavailableMode#DISCONNECT DISCONNECT} mode.
 */
@ReplayLogsOnFailure
@InjectFields
public class InitializationFailureTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"InitializationFailureTest",
			MongoDriverSettings.builder()
				.database(InitializationFailureTest.class.getSimpleName())
				.timescaleMS(SHORT_TIMESCALE)
				.initialDatabaseUnavailableMode(FAIL_FAST)));
	}

	@Test
	@DisruptsMongoProxy
	void initialOutage_throws(TestInfo testInfo) {
		logController.setLogging(ERROR, ChangeReceiver.class);
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());
		assertThrows(InitialStateFailureException.class, ()->{
			new Bosk<>(
				boskName("Fail"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder()
					.driverFactory(super.createDriverFactory(logController, testInfo))
					.build()
			);
		});
	}

}
