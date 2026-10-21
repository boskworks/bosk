package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import works.bosk.DriverFactory;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.ErrorRecordingChangeListener.ErrorRecorder;
import works.bosk.drivers.mongo.internal.MainDriver.MongoClientFactory;
import works.bosk.drivers.mongo.internal.TestParameters.EventTiming;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.Injected;
import works.bosk.junit.InjectorMethod;
import works.bosk.testing.drivers.fuzz.AbstractRandomizedDriverConformanceTest;
import works.bosk.testing.drivers.state.TestEntity;

import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;

@InjectFields
class MongoDriverRandomizedConformanceTest extends AbstractRandomizedDriverConformanceTest {
	private static MongoService mongoService;
	private final List<MongoDriver> openDrivers = new ArrayList<>();
	@Injected ParameterSet parameters;
	private MongoDriverSettings driverSettings;
	private MongoClientSettings clientSettings;
	private ErrorRecorder errorRecorder;

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(SEQUOIA, PandoFormat.oneBigDocument()),
			Stream.of(EventTiming.NORMAL));
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@AfterAll
	static void closeClients() {
		SHARED_CLIENTS.values().forEach(MongoClient::close);
	}

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecorder();
		// Reuse clients across drivers, since otherwise each one opens a new pool.
		var defaultFactory = TestProbes.noop().clientFactory();
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream))
			.withFailOnDisruption()
			.withClientFactory(new MongoClientFactory(
				settings -> SHARED_CLIENTS.computeIfAbsent(settings, defaultFactory.function()),
				false
			)));
	}

	@AfterEach
	void teardownErrorRecording() {
		MainDriver.resetProbes();
		errorRecorder.assertAllClear("after test");
	}

	@BeforeEach
	void setupDriverFactory(TestInfo testInfo) {
		driverSettings = parameters.driverSettingsBuilder().build();
		clientSettings = mongoService.clientSettings(testInfo);
	}

	@AfterEach
	void closeDriversAndDropData() {
		closeOpenDrivers();
		dropCollection();
	}

	@Override
	protected DriverFactory<TestEntity> driverFactory() {
		return (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<TestEntity>factory(clientSettings, driverSettings, new BsonSerializer())
				.build(boskInfo, downstream);
			openDrivers.add(driver);
			return driver;
		};
	}

	@Override
	protected int batchCount() {
		// Each batch builds a whole new bosk and MongoDriver, so keep it modest.
		return 25;
	}

	@Override
	protected int sequenceLength() {
		return 8;
	}

	@Override
	protected void beforeBatch() {
		// The next batch starts from a fresh bosk, so the previous driver must go
		// and the collection must be empty for the new one to initialize cleanly.
		closeOpenDrivers();
		dropCollection();
	}

	private void closeOpenDrivers() {
		for (MongoDriver driver : openDrivers) {
			driver.close();
		}
		openDrivers.clear();
	}

	private void dropCollection() {
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection())
			.drop();
	}

	private static final Map<MongoClientSettings, MongoClient> SHARED_CLIENTS = new ConcurrentHashMap<>();
}
