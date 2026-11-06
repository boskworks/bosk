package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.RetryableUpdateException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Exercises the driver-managed transaction retry path for Pando updates.
 * <p>
 * The write interceptor injects transient transaction errors. One test makes
 * every write fail until the MongoDB driver's {@code timeoutMS} budget
 * (derived from {@code timescaleMS}) is exhausted, and verifies that the
 * update surfaces as a retryable failure without disconnecting the driver.
 * The other fails a single write and verifies that the driver retries the
 * transaction and the update succeeds.
 */
@ReplayLogsOnFailure
@InjectFields
class WithTransactionRetryTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"WithTransactionRetryTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.oneBigDocument())
				.timescaleMS(SHORT_TIMESCALE)
				.database("WithTransactionRetryTest")));
	}

	@AfterEach
	void resetProbes() {
		MainDriver.resetProbes();
	}

	@Test
	void exhaustedConflictBudget_throwsRetryableUpdateException_andStaysConnected() throws InvalidTypeException, IOException, InterruptedException {
		AtomicBoolean failWrites = new AtomicBoolean(false);
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(filter -> {
			if (failWrites.get()) {
				throw transientTransactionError();
			}
		}));

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		Reference<TestValues> values = bosk.buildReferences(Refs.class).values();

		failWrites.set(true);
		RetryableUpdateException e = assertThrows(RetryableUpdateException.class,
			() -> bosk.driver().submitReplacement(values, TestValues.blank().withString("first")));
		assertEquals(Duration.ofMillis(SHORT_TIMESCALE), e.retryAfter());

		// The driver must still be usable: the next update succeeds
		// now that the injected conflict is gone.
		failWrites.set(false);
		bosk.driver().submitReplacement(values, TestValues.blank().withString("second"));
		bosk.driver().flush();
	}

	@Test
	void transientConflict_isRetried_andSucceeds() throws InvalidTypeException, IOException, InterruptedException {
		AtomicBoolean failNextWrite = new AtomicBoolean(false);
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(filter -> {
			if (failNextWrite.compareAndSet(true, false)) {
				throw transientTransactionError();
			}
		}));

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		Reference<TestValues> values = bosk.buildReferences(Refs.class).values();

		failNextWrite.set(true);
		bosk.driver().submitReplacement(values, TestValues.blank().withString("retried"));
		bosk.driver().flush();

		try (var _ = bosk.readSession()) {
			assertEquals("retried", bosk.rootReference().value().values().orElseThrow().string());
		}
	}

	private static MongoException transientTransactionError() {
		MongoException e = new MongoException(112, "Simulated WriteConflict");
		e.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
		return e;
	}
}
