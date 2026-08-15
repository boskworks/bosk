package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the {@link TestProbes#onDisruption} probe: when the driver cannot
 * initialize the database and falls back to the downstream initial state, a
 * probe that throws must fail the {@link Bosk} constructor.
 */
@InjectFields
@ReplayLogsOnFailure
class DisruptionProbeTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void initialStateFallback_failsConstructionViaOnDisruptionProbe() {
		// Force the init transaction's commit to fail, and fail on the resulting disruption.
		MainDriver.setProbes(TestProbes.noop()
			.withCommitInterceptor(() -> { throw new MongoException("Forced commit failure"); })
			.withOnDisruption(reason -> { throw new AssertionError("driver disruption during init", reason); }));

		// The constructor must fail because the init fell back and the probe threw.
		assertThrows(AssertionError.class, () -> new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build()));

		MainDriver.resetProbes();
	}

	@Test
	void successfulInitialization_doesNotFireOnDisruptionProbe() throws InvalidTypeException, IOException, InterruptedException {
		AtomicInteger disruptions = new AtomicInteger();
		MainDriver.setProbes(TestProbes.noop()
			.withOnDisruption(reason -> disruptions.incrementAndGet()));

		new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		assertEquals(0, disruptions.get(), "No disruption expected when initialization succeeds");
		MainDriver.resetProbes();
	}

}
