package works.bosk.drivers.mongo.internal;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Entity;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.Injected;
import works.bosk.logback.BoskLogFilter;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * One Bosk's connection to the test MongoDB: the settings that determine how it
 * talks to the database, the factory that creates its driver, and the actions
 * that close what the test created.
 * <p>
 * Most tests use one of these through {@link AbstractMongoDriverTest}, which
 * adds the JUnit lifecycle. A test that needs several Bosks at once, such as
 * {@code SchemaEvolutionTest}, holds one fixture per Bosk directly.
 */
abstract class MongoDriverFixture {
	protected static MongoService mongoService;

	protected @Injected ParameterSet parameters;
	protected BoskLogFilter.LogController logController;
	protected DriverFactory<TestEntity> driverFactory;
	protected Deque<Runnable> tearDownActions;
	protected MongoDriverSettings driverSettings;

	/**
	 * Builds this fixture's settings and driver factory, and empties its
	 * collection so that the test starts from a clean slate.
	 */
	void setupDriverFactory(TestInfo testInfo) {
		this.driverSettings = parameters.driverSettingsBuilder().build();
		logController = new BoskLogFilter.LogController();
		driverFactory = createDriverFactory(logController, testInfo);

		// Start with a clean slate
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection())
			.drop();
	}

	/**
	 * Starts a fresh list of teardown actions and logs the start of the test.
	 */
	void clearTearDown(TestInfo testInfo) {
		logTest("/=== Start", testInfo);
		tearDownActions = new ArrayDeque<>();
//		tearDownActions.addLast(() ->  {
//			try {
//				LOGGER.debug("Sleeping after teardown");
//				Thread.sleep(10_000);
//			} catch (InterruptedException e) {
//				LOGGER.debug("Interrupted", e);
//				Thread.interrupted();
//			} finally {
//				LOGGER.debug("Done sleeping");
//			}
//		});
	}

	/**
	 * Runs the actions this fixture registered for teardown, closing the clients
	 * it created, and logs the end of the test.
	 */
	void tearDown(TestInfo testInfo) {
		tearDownActions.forEach(Runnable::run);
		logTest("\\=== Done", testInfo);
	}

	protected <E extends Entity> DriverFactory<E> createDriverFactory(BoskLogFilter.LogController logController, TestInfo testInfo) {
		DriverFactory<E> mongoDriverFactory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<E>factory(
				mongoService.clientSettings(testInfo),
				driverSettings,
				new BsonSerializer()
			).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return DriverStack.of(
			BoskLogFilter.withController(logController),
			mongoDriverFactory
		);
	}

	protected void suppressLogging(Level level, Class<?>... loggers) {
		logController.suppressLogging(level, loggers);
	}

	public static void logTest(String verb, TestInfo testInfo) {
		String method =
			testInfo.getTestClass().map(Class::getSimpleName).orElse(null)
				+ "."
				+ testInfo.getTestMethod().map(Method::getName).orElse(null);
		LOGGER.info("{} {} {}", verb, method, testInfo.getDisplayName());
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverFixture.class);
}
