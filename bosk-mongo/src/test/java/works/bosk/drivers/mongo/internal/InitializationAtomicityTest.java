package works.bosk.drivers.mongo.internal;

import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.slf4j.event.Level.WARN;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests that initialization writes the state document(s) and the manifest atomically,
 * in both the {@link SequoiaFormatDriver} and {@link PandoFormatDriver} formats, so that
 * a failure partway through initialization doesn't leave the collection half-initialized
 * (state documents with no manifest, or vice versa).
 * <p>
 * The test interposes a {@link WriteInterceptor} that fails the write of the manifest
 * document, which in both formats is the last write of initialization: by then, the state
 * document write has already happened, so it can only be undone if the two writes occur in
 * a transaction. A correct driver must leave the collection completely empty: either both
 * writes succeed or neither does.
 */
@ReplayLogsOnFailure
@InjectFields
public class InitializationAtomicityTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				MongoDriverSettings.DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable")
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		);
	}

	@Test
	void failedInitialization_leavesCollectionEmpty() {
		// This test deliberately provokes a disconnect, so log errors only
		logController.suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);

		MainDriver.setProbes(TestProbes.noop()
			.withWriteInterceptor(filter -> {
				if (isManifestWrite(filter)) {
					throw new IllegalStateException("Test injection: failing the manifest write");
				}
			}));
		try {
			// doInitialState falls back to the downstream initial state when initialization
			// fails, so constructing the bosk succeeds even though the database was never
			// successfully initialized.
			new Bosk<>(
				boskName(getClass().getSimpleName()),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		} finally {
			MainDriver.resetProbes();
		}

		var collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		assertEquals(0, collection.countDocuments(),
			"Collection must be empty after a failed initialization: "
				+ "the state document and manifest writes must be atomic");
	}
}
