package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.junit.Slow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Exercises the {@link CommitInterceptor} probe through the full driver.
 * A commit that reports an unknown result must be retried, so the submit still
 * completes; the {@code commitInterceptor} is consulted once per commit attempt.
 * <p>
 * The Pando format submits its updates inside a multi-document transaction, so this
 * is the format that actually exercises {@link TransactionalCollection.Session#commitTransactionIfAny()}
 * on the submit path. (Sequoia writes each update atomically without a transaction.)
 */
@Slow
@ReplayLogsOnFailure
@InjectFields
public class CommitRetryTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"CommitRetryTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.oneBigDocument())
				.timescaleMS(LONG_TIMESCALE)
				.database("CommitRetryTest")));
	}

	@AfterEach
	void resetProbes() {
		MainDriver.resetProbes();
	}

	@Test
	void submitReplacement_retriesCommit_whenCommitResultUnknown() throws InvalidTypeException, IOException, InterruptedException {
		AtomicInteger commitAttempts = new AtomicInteger();
		AtomicBoolean armCommitFailure = new AtomicBoolean(false);
		MainDriver.setProbes(TestProbes.noop().withCommitInterceptor(() -> {
			if (armCommitFailure.get() && commitAttempts.getAndIncrement() == 0) {
				throw unknownCommitResult();
			}
		}));

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		// The collection-initialization commit must not be disturbed.
		armCommitFailure.set(true);

		Reference<TestValues> values = bosk.buildReferences(Refs.class).values();
		bosk.driver().submitReplacement(values, TestValues.blank());
		bosk.driver().flush();

		assertEquals(2, commitAttempts.get(), "The commit must be retried once after an unknown result");
	}

	private static MongoException unknownCommitResult() {
		MongoException e = new MongoException(117, "Simulated unknown commit result");
		e.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
		return e;
	}
}
