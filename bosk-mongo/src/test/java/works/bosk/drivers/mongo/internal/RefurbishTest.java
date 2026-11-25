package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.drivers.state.UpgradeableEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;


/**
 * Tests the {@link MongoDriver#refurbish} operation: it creates fields that the
 * current schema lacks and repairs stray metadata fields.
 */
@InjectFields
@ReplayLogsOnFailure
public class RefurbishTest extends AbstractMongoDriverTest {
	/**
	 * One recorder per driver stack: the list is only touched on the test
	 * thread, while constructing the bosks, and each receiver writes only its
	 * own recorder.
	 */
	private final List<ErrorRecordingChangeListener.ErrorRecorder> errorRecorders = new ArrayList<>();

	@AfterEach
	void resetErrorRecording() {
		MainDriver.resetProbes();
	}

	/**
	 * Installs a fresh recorder for the driver stack of the next bosk
	 * constructed on this thread.
	 */
	private ErrorRecordingChangeListener.ErrorRecorder installErrorRecorder() {
		var recorder = new ErrorRecordingChangeListener.ErrorRecorder();
		errorRecorders.add(recorder);
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(recorder, downstream)));
		return recorder;
	}

	private void assertOnlyExpectedDisconnections(String description) {
		errorRecorders.forEach(recorder -> {
			assertEquals(0, recorder.failureCount, "No connection failures " + description);
			assertTrue(recorder.disconnections.stream().allMatch(CollectionRewrittenException.class::isInstance),
				"A refurbish may only cause the expected reload " + description + ": " + recorder);
		});
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable")
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s.timescaleMS(LONG_TIMESCALE)));
	}

	@Test
	void refurbish_createsField(TestInfo testInfo) throws IOException, InterruptedException {
		// We'll use this as an honest observer of the actual state
		LOGGER.debug("Create Original bosk");
		var originalErrors = installErrorRecorder();
		Bosk<TestEntity> originalBosk = new Bosk<>(
			boskName("Original"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Create Upgradeable bosk");
		installErrorRecorder();
		Bosk<UpgradeableEntity> upgradeableBosk = new Bosk<>(
			boskName("Upgradeable"),
			UpgradeableEntity.class,
			_ -> { throw new AssertionError("upgradeableBosk should use the state from MongoDB"); },
			BoskConfig.<UpgradeableEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Check state before");
		Optional<TestValues> before;
		try (var _ = originalBosk.readSession()) {
			before = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.empty(), before); // Not there yet

		LOGGER.debug("Call refurbish");
		upgradeableBosk.getDriver(MongoDriver.class).refurbish();
		originalBosk.driver().flush(); // Not the bosk that did refurbish!

		LOGGER.debug("Check state after");
		Optional<TestValues> after;
		try (var _ = originalBosk.readSession()) {
			after = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.of(TestValues.blank()), after); // Now it's there

		assertEquals(0, originalErrors.failureCount, "No connection failures");
		assertTrue(originalErrors.disconnections.stream().allMatch(CollectionRewrittenException.class::isInstance),
			"A refurbish may only cause the expected reload: " + originalErrors);
	}

	@Test
	void refurbish_fixesMetadata(TestInfo testInfo) throws IOException, InterruptedException {
		// Set up the database so it looks basically right
		installErrorRecorder();
		Bosk<TestEntity> initialBosk = new Bosk<>(
			boskName("Initial"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// (Close this so it doesn't crash when we start mucking with the database)
		initialBosk.getDriver(MongoDriver.class).close();

		// Add a bogus metadata field
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		String bogusField = "bogusField";
		addFields(collection, bogusField);

		// Make the bosk whose refurbish operation we want to test
		installErrorRecorder();
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// Get the new bosk reconnected
		bosk.driver().flush();

		// Verify that the fields are indeed there
		BsonDocument filterDoc = rootDocumentsFilter();
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			assertEquals(new BsonString(bogusField), doc.getString(bogusField));
		}

		// Refurbish
		bosk.getDriver(MongoDriver.class).refurbish();

		// Verify the field is now gone
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			assertNull(doc.get(bogusField));
		}

		assertOnlyExpectedDisconnections("after test");
	}

	private void addFields(MongoCollection<BsonDocument> collection, String... fieldNames) {
		BsonDocument fieldsToSet = new BsonDocument();
		for (String fieldName: fieldNames) {
			fieldsToSet.append(fieldName, new BsonString(fieldName));
		}
		BsonDocument filterDoc = rootDocumentsFilter();
		collection.updateOne(
			filterDoc,
			new BsonDocument("$set", fieldsToSet));

		// Make sure they exist
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			for (String fieldName: fieldNames) {
				assertEquals(new BsonString(fieldName), doc.getString(fieldName));
			}
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(RefurbishTest.class);
}
