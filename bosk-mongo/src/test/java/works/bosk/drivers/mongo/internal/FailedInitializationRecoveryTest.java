package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.slf4j.event.Level.WARN;
import static works.bosk.drivers.mongo.internal.MongoService.FailureMode.CLOSE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A failure while initializing the database falls back to the downstream initial
 * state so that the application can start, but the collection is still
 * uninitialized. Bosk aims to be self-repairing: the receiver reconnects on its
 * own, and it must retry the initialization too, so that updates work once the
 * cause clears.
 */
@ReplayLogsOnFailure
@InjectFields
class FailedInitializationRecoveryTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument()
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s.timescaleMS(SHORT_TIMESCALE)));
	}

	@Test
	void failedInitialization_recoversOnceTheFailureClears() throws IOException, InterruptedException, InvalidTypeException {
		// This test deliberately provokes a disconnect, so log errors only.
		suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);

		// Fail more than once, so a retry that fails again is exercised too.
		AtomicInteger manifestWritesToFail = new AtomicInteger(3);
		AtomicInteger failures = new AtomicInteger(0);
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(filter -> {
			if (isManifestWrite(filter) && manifestWritesToFail.getAndDecrement() > 0) {
				failures.incrementAndGet();
				throw new IllegalStateException("Simulated transient initialization failure");
			}
		}));
		Bosk<TestEntity> bosk;
		try {
			bosk = new Bosk<>(
				boskName("failedInitialization"),
				TestEntity.class,
				AbstractMongoDriverTest::initialRoot,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		} finally {
			MainDriver.resetProbes();
		}

		// The injected failure is gone now. An update waits for the driver to
		// reconnect and reinitialize, and fails against the unfixed code.
		Reference<String> rootString = bosk.rootReference().then(String.class, TestEntity.Fields.string);
		bosk.driver().submitReplacement(rootString, "recovered");
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals("recovered", rootString.value(),
				"The driver must retry initialization once the failure clears");
		}
		assertEquals(3, failures.get(), "The initialization and two retries should have failed");
	}

	@Test
	@DisruptsMongoProxy
	void startupWithDatabaseUnreachable_initializesOnceTheDatabaseIsReachable() throws IOException, InterruptedException, InvalidTypeException {
		// This test deliberately provokes a disconnect, so log errors only.
		suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);

		mongoService.disruptConnection(CLOSE);
		tearDownActions.add(() -> mongoService.restoreConnection(CLOSE));

		// The Bosk starts from the downstream state, and the collection is uninitialized.
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("unreachableStartup"),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		mongoService.restoreConnection(CLOSE);

		Reference<String> rootString = bosk.rootReference().then(String.class, TestEntity.Fields.string);
		bosk.driver().submitReplacement(rootString, "recovered");
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals("recovered", rootString.value(),
				"The driver must initialize the collection once the database is reachable");
		}
	}
}
