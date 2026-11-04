package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import works.bosk.libtesting.fuzz.OperationScriptCodec;
import works.bosk.testing.drivers.fuzz.AbstractFuzzRegressionTest;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;

/**
 * Replays the committed fuzz corpus against MongoDriver.
 *
 * <p>The corpus records operations as paths and serialized values, so the same file exercises
 * MongoDriver even though it was generated against an in-memory bosk. Each script is replayed
 * against a freshly initialized driver.
 */
@InjectFields
class MongoDriverFuzzRegressionTest extends AbstractFuzzRegressionTest {
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
	protected List<OperationScript> scripts() {
		try (InputStream in = requireNonNull(getClass().getClassLoader().getResourceAsStream("fuzz-corpus.json"))) {
			return new OperationScriptCodec().read(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	protected void beforeScript() {
		// Each script starts from a fresh bosk, so the previous driver must go
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
