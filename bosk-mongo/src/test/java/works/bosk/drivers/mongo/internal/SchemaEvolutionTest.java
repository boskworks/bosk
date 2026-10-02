package works.bosk.drivers.mongo.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.MainDriver.ManifestInfo;
import works.bosk.drivers.mongo.internal.SchemaEvolutionTest.ConfigInjector;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.Injector;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.logging.MdcKeys;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.testing.BoskTestUtils.boskName;

@InjectFields
@InjectFrom(ConfigInjector.class)
@ReplayLogsOnFailure
public class SchemaEvolutionTest {

	@Injected("from")
	Configuration fromConfig;

	@Injected("to")
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

		try (var _ = fromBosk.readSession()) {
			ManifestInfo expected = fromConfig.expectedManifestInfo();
			LOGGER.debug("Confirm starting manifest is {}", expected);
			assertEquals(
				expected,
				fromBosk.getDriver(MainDriver.class).loadManifestInfo()
			);
		}

		LOGGER.debug("Confirm starting manifest ID");

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


		try (var _ = toBosk.readSession()) {
			ManifestInfo expected = toConfig.expectedManifestInfo();
			LOGGER.debug("Confirm ending manifest is {}", expected);
			assertEquals(
				expected,
				toBosk.getDriver(MainDriver.class).loadManifestInfo()
			);
		}

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

		LOGGER.debug("Verify that the manifest prescribes the preferred format");
		try (var _ = toBosk.readSession()) {
			var status = driver.readStatus();
			assertEquals(
				Manifest.forFormat(toConfig.preferredFormat),
				status.manifest().actual()
			);
		}

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

	@Test
	void refurbishToDifferentFormat_observerReloadsQuietly() throws Exception {
		assumeFalse(fromConfig.preferredFormat().equals(toConfig.preferredFormat()),
			"This test concerns a change to a different format");
		assumeFalse(SEQUOIA == fromConfig.preferredFormat(),
			"Sequoia cannot reliably handle writes during a format change");

		// Signal when the observer's receiver processes the new manifest.
		CountDownLatch observerDisconnected = new CountDownLatch(1);
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(listener ->
			new ForwardingChangeListener(listener) {
				@Override
				public void onDisconnect(Throwable e) {
					observerDisconnected.countDown();
					super.onDisconnect(e);
				}
			}));
		Bosk<TestEntity> fromBosk = newBosk(fromHelper);
		Refs fromRefs = fromBosk.buildReferences(Refs.class);
		MainDriver.resetProbes();

		// Capture warnings from the observer's ChangeReceiver.
		// The MDC is read inside append() so that it reflects the logging thread.
		String observerID = fromBosk.instanceID().toString();
		List<String> observerWarnings = Collections.synchronizedList(new ArrayList<>());
		ch.qos.logback.classic.Logger changeReceiverLogger =
			(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ChangeReceiver.class);
		AppenderBase<ILoggingEvent> warningRecorder = new AppenderBase<>() {
			@Override
			protected void append(ILoggingEvent event) {
				if (observerID.equals(event.getMDCPropertyMap().get(MdcKeys.BOSK_INSTANCE_ID))) {
					observerWarnings.add(event.getFormattedMessage());
				}
			}
		};
		warningRecorder.setContext(changeReceiverLogger.getLoggerContext());
		warningRecorder.setName("observerWarnings");
		warningRecorder.start();
		changeReceiverLogger.addAppender(warningRecorder);
		try {
			// The class-level setup suppresses these; allow warnings through so we can detect them.
			fromHelper.setLogging(Level.INFO, ChangeReceiver.class);

			LOGGER.debug("Refurbish toBosk to a different format ({})", toConfig);
			newBosk(toHelper).getDriver(MongoDriver.class).refurbish();

			assertTrue(observerDisconnected.await(30, SECONDS),
				"The observer must notice the format change");

			// The observer is now reloading; a write must wait for it and then succeed.
			LOGGER.debug("Write from fromBosk ({}) while it reloads the new format", fromBosk.name());
			fromBosk.driver().submitReplacement(fromRefs.string(), "Distinctive String");
			fromBosk.driver().flush();

			try (var _ = fromBosk.readSession()) {
				assertEquals("Distinctive String", fromRefs.string().value(),
					"The write must survive the format change");
			}

			assertEquals(List.of(), observerWarnings,
				"A format change on another replica must not log a warning");
		} finally {
			MainDriver.resetProbes();
			changeReceiverLogger.detachAppender(warningRecorder);
			warningRecorder.stop();
		}
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
		return new Bosk<>(
			boskName(helper.toString()),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(helper.driverFactory)
				.build()
		);
	}

	record Configuration(DatabaseFormat preferredFormat) {
		public ManifestInfo expectedManifestInfo() {
			return new ManifestInfo(
				Manifest.forFormat(preferredFormat),
				MANIFEST_ID
			);
		}

		@Override
		public String toString() {
			return preferredFormat.toString();
		}
	}

	record ConfigInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == Configuration.class;
		}

		@Override
		public List<Configuration> values() {
			return CONFIGURATIONS;
		}
	}

	private static final List<Configuration> CONFIGURATIONS = Stream.of(
		SEQUOIA,
		PandoFormat.oneBigDocument(),
		PandoFormat.withGraftPoints("/catalog", "/sideTable")
	).map(Configuration::new).toList();

	static final class Helper extends AbstractMongoDriverTest {
		final String name;

		public Helper(Configuration config, int dbCounter) {
			// Helpers are instantiated directly rather than by JUnit, so there's
			// no injection machinery to supply `parameters`; set it here instead.
			this.parameters = new ParameterSet(
				SchemaEvolutionTest.class.getSimpleName() + "_" + dbCounter,
				MongoDriverSettings.builder()
					.database(SchemaEvolutionTest.class.getSimpleName() + "_" + dbCounter)
					.preferredDatabaseFormat(config.preferredFormat()));
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
