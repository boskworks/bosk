package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
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

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
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
				PandoFormat.oneBigDocument()
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		);
	}

	@Test
	void failedInitialization_leavesCollectionEmpty() {
		// This test deliberately provokes a disconnect, so log errors only
		logController.setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		MainDriver.TEST_PROBES.set(TestProbes.noop()
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
			MainDriver.TEST_PROBES.remove();
		}

		var collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME, BsonDocument.class);
		assertEquals(0, collection.countDocuments(),
			"Collection must be empty after a failed initialization: "
				+ "the state document and manifest writes must be atomic");
	}

	private static boolean isManifestWrite(Bson filter) {
		BsonDocument asDoc = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		BsonValue idValue = asDoc.get("_id");
		return MANIFEST_ID.equals(idValue);
	}
}
