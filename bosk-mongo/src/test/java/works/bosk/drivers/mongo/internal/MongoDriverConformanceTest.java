package works.bosk.drivers.mongo.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.EventTiming;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.ParameterInjector;
import works.bosk.testing.drivers.SharedDriverConformanceTest;
import works.bosk.testing.junit.Slow;

import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;

@Slow
@InjectFrom(MongoDriverConformanceTest.Injector.class)
class MongoDriverConformanceTest extends SharedDriverConformanceTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;
	private final MongoDriverSettings driverSettings;
	private final AtomicInteger numOpenDrivers = new AtomicInteger(0);

	public MongoDriverConformanceTest(ParameterSet parameters) {
		this.driverSettings = parameters.driverSettingsBuilder().build();
	}

	record Injector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(java.lang.reflect.Parameter parameter) {
			return parameter.getType() == ParameterSet.class;
		}

		@Override
		public List<Object> values() {
			return TestParameters.driverSettings(
					Stream.of(
						PandoFormat.oneBigDocument(),
//						PandoFormat.withGraftPoints("/catalog", "/sideTable"), // Basic
//						PandoFormat.withGraftPoints("/nestedSideTable"), // Documents are themselves side tables
						PandoFormat.withGraftPoints("/nestedSideTable/-x-"), // Graft points are side table entries
//						PandoFormat.withGraftPoints("/catalog/-x-/sideTable", "/sideTable/-x-/catalog", "/sideTable/-x-/sideTable/-y-/catalog"), // Nesting, parameters
//						PandoFormat.withGraftPoints("/sideTable/-x-/sideTable/-y-/catalog"), // Multiple parameters in the not-separated part
						SEQUOIA
					),
					Stream.of(EventTiming.NORMAL)) // EARLY is slow; LATE is really slow
				.map(x -> (Object)x)
				.toList();
		}
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setupDriverFactory(TestInfo testInfo) {
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

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverConformanceTest.class);
}
