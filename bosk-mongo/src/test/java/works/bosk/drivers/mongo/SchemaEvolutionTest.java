package works.bosk.drivers.mongo;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.state.TestEntity;
import works.bosk.junit.ParametersByName;
import works.bosk.junit.Slow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.BoskTestUtils.boskName;

@Slow
public class SchemaEvolutionTest {

	private final Helper fromHelper;
	private final Helper toHelper;

	@ParametersByName
	SchemaEvolutionTest(Configuration fromConfig, Configuration toConfig) {
		int dbCounter = DB_COUNTER.incrementAndGet();
		this.fromHelper = new Helper(fromConfig, dbCounter);
		this.toHelper = new Helper(toConfig, dbCounter);
	}

	@BeforeAll
	static void beforeAll() {
		AbstractMongoDriverTest.setupMongoConnection();
	}

	@BeforeEach
	void beforeEach(TestInfo testInfo) {
		fromHelper.setupDriverFactory(testInfo);
		toHelper  .setupDriverFactory(testInfo);

		// Changing formats often causes events that are not understood by other FormatDrivers
		fromHelper.setLogging(Level.ERROR, ChangeReceiver.class);
		toHelper.setLogging(Level.ERROR, ChangeReceiver.class);

		fromHelper.clearTearDown(testInfo);
		toHelper  .clearTearDown(testInfo);
	}

	@AfterEach
	void afterEach(TestInfo testInfo) {
		fromHelper.runTearDown(testInfo);
		toHelper  .runTearDown(testInfo);
	}

	@SuppressWarnings("unused")
	static Stream<Configuration> fromConfig() {
		return Stream.of(
			new Configuration(MongoDriverSettings.DatabaseFormat.SEQUOIA),
			new Configuration(PandoFormat.oneBigDocument()),
			new Configuration(PandoFormat.withGraftPoints("/catalog", "/sideTable"))
		);
	}

	@SuppressWarnings("unused")
	static Stream<Configuration> toConfig() {
		return fromConfig();
	}

	@ParametersByName
	void pairwise_readCompatible() throws Exception {
		LOGGER.debug("Create fromBosk [{}]", fromHelper.name);
		Bosk<TestEntity> fromBosk = newBosk(fromHelper);
		Refs fromRefs = fromBosk.buildReferences(Refs.class);

		LOGGER.debug("Set distinctive string");
		fromBosk.driver().submitReplacement(fromRefs.string(), "Distinctive String");
		fromBosk.driver().flush();

		LOGGER.debug("Create toBosk [{}]", toHelper.name);
		Bosk<TestEntity> toBosk = newBosk(toHelper);
		Refs toRefs = toBosk.buildReferences(Refs.class);

		LOGGER.debug("Perform toBosk read");
		try (var _ = toBosk.readContext()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}

		LOGGER.debug("Refurbish");
		MongoDriver driver = toBosk.getDriver(MongoDriver.class);
		driver.refurbish();

		LOGGER.debug("Perform fromBosk read");
		try (var _ = fromBosk.readContext()) {
			assertEquals("Distinctive String", fromRefs.string().value());
		}

		LOGGER.debug("Perform toBosk read");
		try (var _ = toBosk.readContext()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}

//		System.out.println("Status: " + ((MongoDriver<?>)toBosk.driver()).readStatus());
	}

	@ParametersByName
	void pairwise_writeCompatible() throws Exception {
		LOGGER.debug("Create fromBosk [{}]", fromHelper.name);
		Bosk<TestEntity> fromBosk = newBosk(fromHelper);
		Refs fromRefs = fromBosk.buildReferences(Refs.class);

		LOGGER.debug("Create toBosk [{}]", toHelper.name);
		Bosk<TestEntity> toBosk = newBosk(toHelper);
		Refs toRefs = toBosk.buildReferences(Refs.class);

		LOGGER.debug("Refurbish toBosk ({})", toBosk.name());
		MongoDriver driver = toBosk.getDriver(MongoDriver.class);
		driver.refurbish();

		flushIfLiveRefurbishIsNotSupported(fromBosk, fromHelper, toHelper);

		LOGGER.debug("Set distinctive string using fromBosk ({})", fromBosk.name());
		fromBosk.driver().submitReplacement(fromRefs.string(), "Distinctive String");
		LOGGER.debug("Flush fromBosk ({})", fromBosk.name());
		fromBosk.driver().flush();

		LOGGER.debug("Perform fromBosk ({}) read", fromBosk.name());
		try (var _ = fromBosk.readContext()) {
			assertEquals("Distinctive String", fromRefs.string().value());
		}

		LOGGER.debug("Flush toBosk({}) to see the update", toBosk.name());
		toBosk.driver().flush();

		LOGGER.debug("Perform toBosk ({}) read", toBosk.name());
		try (var _ = toBosk.readContext()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}

//		System.out.println("Status: " + ((MongoDriver<?>)toBosk.driver()).readStatus());
	}

	/**
	 * @param boskForUpdate      {@link Bosk} that is about to submit an update
	 * @param helperForUpdate    {@link Helper} for the bosk that is about to submit an update
	 * @param helperForRefurbish {@link Helper} for the bosk that performed the {@link MongoDriver#flush()} operation
	 */
	private static void flushIfLiveRefurbishIsNotSupported(
		Bosk<TestEntity> boskForUpdate,
		Helper helperForUpdate,
		Helper helperForRefurbish
	) throws IOException, InterruptedException {
		if (helperForUpdate.driverSettings.preferredDatabaseFormat() == MongoDriverSettings.DatabaseFormat.SEQUOIA
			&& helperForRefurbish.driverSettings.preferredDatabaseFormat() != MongoDriverSettings.DatabaseFormat.SEQUOIA) {
			// When switching format classes, the old format's state documents
			// disappear.
			//
			// Sequoia, being a fundamentally single-document format that does not
			// use transactions, is not able to handle the time window between
			// when that document disappears and when the corresponding change
			// event arrives. We could add complexity to Sequoia to cope with
			// this situation, but the point of Sequoia is its simplicity.
			//
			// Instead, we have a documented limitation that refurbishing from
			// Sequoia to another format has a risk that updates occurring during
			// a certain window could be ignored, and recommend that refurbish
			// operation occur during a period of quiescence.
			//
			// Performing a flush causes Sequoia to "notice" its document is gone
			// and correctly reinitialize itself.

			LOGGER.debug("Flush so boskForUpdate notices the refurbish ({})", boskForUpdate.name());
			boskForUpdate.driver().flush();
		}
	}

	private static Bosk<TestEntity> newBosk(Helper helper) {
		return new Bosk<>(boskName(helper.toString()), TestEntity.class, helper::initialRoot, helper.driverFactory, Bosk.simpleRegistrar());
	}

	record Configuration(
		MongoDriverSettings.DatabaseFormat preferredFormat
	) {
		@Override
		public String toString() {
			return preferredFormat.toString();
		}
	}

	static final class Helper extends AbstractMongoDriverTest {
		final String name;

		public Helper(Configuration config, int dbCounter) {
			super(MongoDriverSettings.builder()
				.database(SchemaEvolutionTest.class.getSimpleName() + "_" + dbCounter)
				.preferredDatabaseFormat(config.preferredFormat())
				.experimental(MongoDriverSettings.Experimental.builder()
					.build()));
			this.name = config.preferredFormat().toString().toLowerCase();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public interface Refs {
		@ReferencePath("/string")
		Reference<String> string();
	}

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);
	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaEvolutionTest.class);
}
