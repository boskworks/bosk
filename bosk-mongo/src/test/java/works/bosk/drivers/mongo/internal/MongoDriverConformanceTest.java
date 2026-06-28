package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.MongoDriverSettings.TenancyFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.ErrorRecordingChangeListener.ErrorRecorder;
import works.bosk.drivers.mongo.internal.MainDriver.MongoClientFactory;
import works.bosk.drivers.mongo.internal.MongoDriverConformanceTest.ParameterSetInjector;
import works.bosk.drivers.mongo.internal.MongoDriverConformanceTest.TenancyFormatInjector;
import works.bosk.drivers.mongo.internal.TestParameters.EventTiming;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.Injector;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.AbstractDriverTest.MultiTreeScenarioInjector;
import works.bosk.testing.drivers.PolyfillDriverConformanceTest;
import works.bosk.testing.junit.Slow;

import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;

@Slow
@InjectFields
@ReplayLogsOnFailure
@InjectFrom({MultiTreeScenarioInjector.class, TenancyFormatInjector.class, ParameterSetInjector.class})
class MongoDriverConformanceTest extends PolyfillDriverConformanceTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;
	@Injected ParameterSet parameters;
	private MongoDriverSettings driverSettings;
	private final AtomicInteger numOpenDrivers = new AtomicInteger(0);
	private ErrorRecorder errorRecorder;

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecorder();
		MainDriver.LISTENER_FACTORY.set(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream));

		// This guy uses a literal bazillion TCP ports if we don't share clients
		var defaultFactory = MainDriver.MONGO_CLIENT_FACTORY.get();
		MainDriver.MONGO_CLIENT_FACTORY.set(new MongoClientFactory(
			settings -> SHARED_CLIENTS.computeIfAbsent(settings, defaultFactory.function()),
			false
		));
	}

	@AfterEach
	void teardownErrorRecording() {
		MainDriver.MONGO_CLIENT_FACTORY.remove();
		errorRecorder.assertAllClear("after test");
		MainDriver.LISTENER_FACTORY.remove();
	}

	@AfterAll
	static void closeClients() {
		SHARED_CLIENTS.values().forEach(MongoClient::close);
	}

	record TenancyFormatInjector(Scenario scenario) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == TenancyFormat.class;
		}

		@Override
		public List<?> values() {
			return switch (scenario) {
				case NO_TENANTS -> List.of(TenancyFormat.NONE);
				case FIXED_TENANT -> List.of(TenancyFormat.NONE, TenancyFormat.ID_PREFIX);
				case PERSISTENT_TENANT -> List.of(TenancyFormat.ID_PREFIX);
			};
		}
	}

	record ParameterSetInjector(Scenario scenario, TenancyFormat tenancyFormat) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == ParameterSet.class;
		}

		@Override
		public List<?> values() {
			Stream<DatabaseFormat> formats = switch (scenario) {
				case NO_TENANTS, FIXED_TENANT ->
					Stream.concat(sequoiaFormats(), pandoFormats());
				case PERSISTENT_TENANT ->
					pandoFormats();
			};
			return TestParameters.driverSettings(
					formats,
					Stream.of(EventTiming.NORMAL)) // EARLY is slow; LATE is really slow
				.toList();
		}

		private Stream<DatabaseFormat> sequoiaFormats() {
			return Stream.of(SEQUOIA);
		}

		private Stream<DatabaseFormat> pandoFormats() {
			return Stream.of(
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable"), // Exercises pre-deletion
				PandoFormat.withGraftPoints("/nestedSideTable/-x-") // Graft points are side table entries
//				PandoFormat.withGraftPoints("/nestedSideTable"), // Documents are themselves side tables
//				PandoFormat.withGraftPoints("/catalog/-x-/sideTable", "/sideTable/-x-/catalog", "/sideTable/-x-/sideTable/-y-/catalog"), // Nesting, parameters
//				PandoFormat.withGraftPoints("/sideTable/-x-/sideTable/-y-/catalog"), // Multiple parameters in the not-separated part
			).map(f -> f.withTenancyFormat(tenancyFormat));
		}

	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setupDriverFactory(TestInfo testInfo) {
		driverSettings = parameters.driverSettingsBuilder().build();
		driverFactory = createDriverFactory(testInfo);
	}

	@AfterEach
	void runTearDown() {
		tearDownActions.forEach(Runnable::run);
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME)
			.drop();
	}

	private <R extends StateTreeNode> DriverFactory<R> createDriverFactory(TestInfo testInfo) {
		return (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<R>factory(
				mongoService.clientSettings(testInfo), driverSettings, new BsonSerializer()
			).build(boskInfo, downstream);
			LOGGER.debug("Driver created; {} open", numOpenDrivers.incrementAndGet());
			tearDownActions.addFirst(() -> {
				driver.close();
				LOGGER.debug("Driver closed; {} remaining", numOpenDrivers.decrementAndGet());
			});
			return driver;
		};
	}

	private static final Map<MongoClientSettings, MongoClient> SHARED_CLIENTS = new ConcurrentHashMap<>();

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverConformanceTest.class);
}
