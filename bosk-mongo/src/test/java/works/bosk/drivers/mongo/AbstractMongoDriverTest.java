package works.bosk.drivers.mongo;

import ch.qos.logback.classic.Level;
import com.mongodb.MongoClientSettings;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import works.bosk.drivers.mongo.bson.BsonSerializer;
import works.bosk.drivers.state.TestEntity;
import works.bosk.drivers.state.TestValues;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.logback.BoskLogFilter;

import static java.util.concurrent.TimeUnit.SECONDS;
import static works.bosk.drivers.mongo.MainDriver.COLLECTION_NAME;

abstract class AbstractMongoDriverTest {
	protected static final Identifier entity123 = Identifier.from("123");
	protected static final Identifier entity124 = Identifier.from("124");
	protected static final Identifier rootID = Identifier.from("root");

	protected static MongoService mongoService;
	protected BoskLogFilter.LogController logController;
	protected DriverFactory<TestEntity> driverFactory;
	protected Deque<Runnable> tearDownActions;
	protected final MongoDriverSettings driverSettings;

	public AbstractMongoDriverTest(MongoDriverSettingsBuilder driverSettings) {
		this.driverSettings = driverSettings.build();
	}


	@BeforeAll
	public static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setupDriverFactory(TestInfo testInfo) {
		logController = new BoskLogFilter.LogController();
		driverFactory = createDriverFactory(logController, testInfo);

		// Start with a clean slate
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME)
			.drop();
	}

	@BeforeEach
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

	@AfterEach
	void runTearDown(TestInfo testInfo) {
		tearDownActions.forEach(Runnable::run);
		logTest("\\=== Done", testInfo);
	}

	public static void logTest(String verb, TestInfo testInfo) {
		String method =
			testInfo.getTestClass().map(Class::getSimpleName).orElse(null)
				+ "."
				+ testInfo.getTestMethod().map(Method::getName).orElse(null);
		LOGGER.info("{} {} {}", verb, method, testInfo.getDisplayName());
	}


	public TestEntity initialRoot(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		Refs refs = testEntityBosk.buildReferences(Refs.class);
		return initialRootWithEmptyCatalog(testEntityBosk)
			.withCatalog(Catalog.of(
				TestEntity.empty(entity123, refs.childCatalog(entity123)),
				TestEntity.empty(entity124, refs.childCatalog(entity124))
			));
	}

	public TestEntity initialRootWithValues(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		return initialRootWithEmptyCatalog(testEntityBosk)
			.withValues(Optional.of(TestValues.blank()));
	}

	public TestEntity initialRootWithEmptyCatalog(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		Refs refs = testEntityBosk.buildReferences(Refs.class);
		return new TestEntity(rootID,
			rootID.toString(),
			Catalog.empty(),
			Listing.of(refs.catalog(), entity123),
			SideTable.empty(refs.catalog()),
			TaggedUnion.of(new TestEntity.StringCase(rootID.toString())),
			Optional.empty()
		);
	}

	protected <E extends Entity> DriverFactory<E> createDriverFactory(BoskLogFilter.LogController logController, TestInfo testInfo) {
		DriverFactory<E> mongoDriverFactory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<E>factory(
				MongoClientSettings.builder(mongoService.clientSettings(testInfo))
					.applyToClusterSettings(builder -> {
						builder.serverSelectionTimeout(5, SECONDS);
					})
					.applyToSocketSettings(builder -> {
						// We're testing timeouts. Let's not wait too long.
						builder.readTimeout(5, SECONDS);
					})
					.build(),
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

	public interface Refs {
		@ReferencePath("/catalog") CatalogReference<TestEntity> catalog();
		@ReferencePath("/catalog/-child-/catalog") CatalogReference<TestEntity> childCatalog(Identifier child);
		@ReferencePath("/listing") ListingReference<TestEntity> listing();
		@ReferencePath("/listing/-entity-") Reference<ListingEntry> listingEntry(Identifier entity);
		@ReferencePath("/values") Reference<TestValues> values();
		@ReferencePath("/values/string") Reference<String> valuesString();
	}

	protected void setLogging(Level level, Class<?>... loggers) {
		logController.setLogging(level, loggers);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMongoDriverTest.class);
}
