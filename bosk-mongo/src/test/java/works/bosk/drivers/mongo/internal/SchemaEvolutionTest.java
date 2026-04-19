package works.bosk.drivers.mongo.internal;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.Injector;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.junit.Slow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.testing.BoskTestUtils.boskName;

@Slow
@InjectFields
@InjectFrom({SchemaEvolutionTest.FromConfigInjector.class, SchemaEvolutionTest.ToConfigInjector.class})
public class SchemaEvolutionTest {

	@Injected
	@From
	Configuration fromConfig;

	@Injected
	@To
	Configuration toConfig;

	private Helper fromHelper;
	private Helper toHelper;

	@BeforeEach
	void setup() {
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

	@Test
	void pairwise_readCompatible() throws Exception {
		LOGGER.debug("Create fromBosk [{}]", fromHelper.name);
		Bosk<TestEntity> fromBosk = newBosk(fromHelper);
		Refs fromRefs = fromBosk.buildReferences(Refs.class);

		// TODO: Make this test less lame
		LOGGER.debug("Set distinctive string");
		fromBosk.driver().submitReplacement(fromRefs.string(), "Distinctive String");
		fromBosk.driver().flush();

		LOGGER.debug("Create toBosk [{}]", toHelper.name);
		Bosk<TestEntity> toBosk = newBosk(toHelper);
		Refs toRefs = toBosk.buildReferences(Refs.class);

		LOGGER.debug("Perform toBosk read");
		try (var _ = toBosk.readSession()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}

		LOGGER.debug("Refurbish");
		MongoDriver driver = toBosk.getDriver(MongoDriver.class);
		driver.refurbish();

		LOGGER.debug("Perform fromBosk read");
		try (var _ = fromBosk.readSession()) {
			assertEquals("Distinctive String", fromRefs.string().value());
		}

		LOGGER.debug("Perform toBosk read");
		try (var _ = toBosk.readSession()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}

//		System.out.println("Status: " + ((MongoDriver<?>)toBosk.driver()).readStatus());
	}

	@Test
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
		try (var _ = fromBosk.readSession()) {
			assertEquals("Distinctive String", fromRefs.string().value());
		}

		LOGGER.debug("Flush toBosk({}) to see the update", toBosk.name());
		toBosk.driver().flush();

		LOGGER.debug("Perform toBosk ({}) read", toBosk.name());
		try (var _ = toBosk.readSession()) {
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
		if (SEQUOIA == helperForUpdate.driverSettings.preferredDatabaseFormat()
			&& SEQUOIA != helperForRefurbish.driverSettings.preferredDatabaseFormat()) {
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
		return new Bosk<>(boskName(helper.toString()), TestEntity.class, AbstractMongoDriverTest::initialState, BoskConfig.<TestEntity>builder().driverFactory(helper.driverFactory).build());
	}

	record Configuration(
		MongoDriverSettings.DatabaseFormat preferredFormat
	) {
		@Override
		public String toString() {
			return preferredFormat.toString();
		}
	}

	static abstract class ConfigInjector implements Injector {
		private final Class<? extends java.lang.annotation.Annotation> annotationType;

		ConfigInjector(Class<? extends java.lang.annotation.Annotation> annotationType) {
			this.annotationType = annotationType;
		}

		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return element.isAnnotationPresent(annotationType)
				&& elementType == Configuration.class;
		}

		@Override
		public List<Configuration> values() {
			return CONFIGURATIONS;
		}
	}

	static class FromConfigInjector extends ConfigInjector {
		FromConfigInjector() {
			super(From.class);
		}
	}

	static class ToConfigInjector extends ConfigInjector {
		ToConfigInjector() {
			super(To.class);
		}
	}

	private static final List<Configuration> CONFIGURATIONS = List.of(
		new Configuration(SEQUOIA),
		new Configuration(PandoFormat.oneBigDocument()),
		new Configuration(PandoFormat.withGraftPoints("/catalog", "/sideTable"))
	);

	static final class Helper extends AbstractMongoDriverTest {
		final String name;

		public Helper(Configuration config, int dbCounter) {
			super(MongoDriverSettings.builder()
				.database(SchemaEvolutionTest.class.getSimpleName() + "_" + dbCounter)
				.preferredDatabaseFormat(config.preferredFormat())
			);
			this.name = config.preferredFormat().toString().toLowerCase(Locale.ROOT);
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);
	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaEvolutionTest.class);
}
