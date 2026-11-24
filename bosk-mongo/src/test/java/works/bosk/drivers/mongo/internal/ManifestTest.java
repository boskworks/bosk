package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.slf4j.event.Level.WARN;
import static works.bosk.TypeValidation.validateType;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the manifest: the driver must reject an unsupported manifest version
 * and validate the manifest's type.
 */
@InjectFields
@ReplayLogsOnFailure
public class ManifestTest extends AbstractMongoDriverTest {
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
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream))
			.withFailOnDisruption());
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
	void manifestVersionBump_disconnects(TestInfo testInfo) throws IOException, InterruptedException {
		suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);

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
			.getCollection(driverSettings.collection());
		collection.updateOne(
			new BsonDocument("_id", new BsonString(MANIFEST_ID)),
			new BsonDocument("$inc", new BsonDocument("version", new BsonInt32(1)))
		);

		LOGGER.debug("Flush should throw");
		assertThrows(FlushFailureException.class, ()->bosk.driver().flush());

		LOGGER.debug("Finished");
	}


	@Test
	void manifest_passesTypeValidation() throws InvalidTypeException {
		validateType(Manifest.class);
	}


	private static final Logger LOGGER = LoggerFactory.getLogger(ManifestTest.class);
}
