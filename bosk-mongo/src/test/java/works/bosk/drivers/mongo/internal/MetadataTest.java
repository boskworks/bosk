package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.stream.Stream;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.junit.Slow;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.TypeValidation.validateType;
import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the collection metadata the driver depends on: the manifest, and the
 * document fields other than {@code state} (such as the revision). The driver
 * must detect corrupted or incompatible metadata and surface it as a checked
 * failure rather than misbehaving.
 */
@InjectFields
@ReplayLogsOnFailure
public class MetadataTest extends AbstractMongoDriverTest {
	/**
	 * We deliberately don't reference {@link MainDriver#MANIFEST_ID} here
	 * because if we change the manifest ID then that's a breaking change,
	 * and we want this test to fail.
	 */
	public static final String MANIFEST_ID = "!Manifest";

	ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream)));
	}

	@AfterEach
	void resetErrorRecording() {
		MainDriver.TEST_PROBES.remove();
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	@Slow
	void revisionFieldWrongType_flushThrowsFlushFailureException() throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("revisionWrongType"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		try (var _ = bosk.readSession()) {
			assertEquals(initialRoot(bosk), bosk.rootReference().value());
		}

		// Corrupt the revision field by giving it the wrong BSON type.
		// The $exists filter targets only the document(s) that have a revision field,
		// which is the root document in both formats.
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME, BsonDocument.class)
			.updateMany(
				new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonDocument("$exists", BsonBoolean.TRUE)),
				new BsonDocument("$set", new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonString("oops")))
			);

		// A malformed revision must surface as a checked FlushFailureException at the driver
		// boundary, never as a raw RuntimeException like BsonInvalidOperationException.
		assertThrows(FlushFailureException.class, () -> bosk.driver().flush());
	}

	@Test
	@Slow
	void manifestVersionBump_disconnects(TestInfo testInfo) throws IOException, InterruptedException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Flush should work");
		bosk.driver().flush();

		errorRecorder.assertAllClear("before manifest version bump");

		LOGGER.debug("Upgrade to an unsupported manifest version");
		MongoCollection<Document> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME);
		collection.updateOne(
			new BsonDocument("_id", new BsonString(MANIFEST_ID)),
			new BsonDocument("$inc", new BsonDocument("version", new BsonInt32(1)))
		);
		// Must also bump the revision number or else flush rightly does nothing
		collection.updateOne(
			rootDocumentsFilter(),
			new BsonDocument("$inc", new BsonDocument("revision", new BsonInt64(1)))
		);

		LOGGER.debug("Flush should throw");
		assertThrows(FlushFailureException.class, ()->bosk.driver().flush());

		LOGGER.debug("Finished");
	}


	@Test
	void manifest_passesTypeValidation() throws InvalidTypeException {
		validateType(Manifest.class);
	}


	private static final Logger LOGGER = LoggerFactory.getLogger(MetadataTest.class);
}
