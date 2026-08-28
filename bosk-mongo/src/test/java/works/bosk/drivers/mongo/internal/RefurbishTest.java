package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.drivers.state.UpgradeableEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static works.bosk.testing.BoskTestUtils.boskName;


/**
 * Tests the {@link MongoDriver#refurbish} operation: it creates fields that the
 * current schema lacks, repairs stray metadata fields, and preserves the
 * existing epoch while adding a missing one.
 */
@InjectFields
@ReplayLogsOnFailure
public class RefurbishTest extends AbstractMongoDriverTest {
	ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream)));
	}

	@AfterEach
	void resetErrorRecording() {
		MainDriver.resetProbes();
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void refurbish_createsField(TestInfo testInfo) throws IOException, InterruptedException {
		// We'll use this as an honest observer of the actual state
		LOGGER.debug("Create Original bosk");
		Bosk<TestEntity> originalBosk = new Bosk<>(
			boskName("Original"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Create Upgradeable bosk");
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

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void refurbish_fixesMetadata(TestInfo testInfo) throws IOException, InterruptedException {
		// Set up the database so it looks basically right
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

	}

	@Test
	void refurbish_preservesExistingEpoch(TestInfo testInfo) throws IOException, InterruptedException {
		// Make the bosk whose refurbish operation we want to test
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// Get the new bosk connected, which initializes the collection with an epoch
		bosk.driver().flush();

		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		BsonDocument filterDoc = rootDocumentsFilter();
		BsonString epochBefore;
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			epochBefore = cursor.next().getString(Formatter.DocumentFields.epoch.name());
		}

		// Refurbish
		bosk.getDriver(MongoDriver.class).refurbish();

		// The existing epoch must be preserved exactly
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonString epochAfter = cursor.next().getString(Formatter.DocumentFields.epoch.name());
			assertEquals(epochBefore, epochAfter);
		}

	}

	@Test
	void refurbish_addsMissingEpoch(TestInfo testInfo) throws IOException, InterruptedException {
		// Set up the database so it looks basically right
		Bosk<TestEntity> initialBosk = new Bosk<>(
			boskName("Initial"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());
		initialBosk.getDriver(MongoDriver.class).close();

		// Simulate a legacy collection by removing the epoch field
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		deleteFields(collection, Formatter.DocumentFields.epoch);

		// Make the bosk whose refurbish operation we want to test
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// Get the new bosk connected
		bosk.driver().flush();

		// Refurbish
		bosk.getDriver(MongoDriver.class).refurbish();

		// The missing epoch must now be present, and must be a plausible UUID
		BsonDocument filterDoc = rootDocumentsFilter();
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonString epoch = cursor.next().getString(Formatter.DocumentFields.epoch.name());
			assertNotNull(epoch);
			UUID.fromString(epoch.getValue());
		}
	}

	private void deleteFields(MongoCollection<BsonDocument> collection, Formatter.DocumentFields... fields) {
		BsonDocument fieldsToUnset = new BsonDocument();
		for (Formatter.DocumentFields field: fields) {
			fieldsToUnset.append(field.name(), BsonNull.VALUE); // Value is ignored
		}
		BsonDocument filterDoc = rootDocumentsFilter();
		collection.updateOne(
			filterDoc,
			new BsonDocument("$unset", fieldsToUnset));

		// Let's just make sure they're gone
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			for (Formatter.DocumentFields field: fields) {
				assertNull(doc.get(field.name()));
			}
		}

		errorRecorder.assertAllClear("after test");
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

		errorRecorder.assertAllClear("after test");
	}


	private static final Logger LOGGER = LoggerFactory.getLogger(RefurbishTest.class);
}
