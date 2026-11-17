package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.DriverFactory;
import works.bosk.Reference;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.libtesting.BlockingGate;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A {@code refurbish} rewrites the database from the state it loads,
 * so this bosk must end up at least as up to date as that state.
 * It learns about the rewritten documents from the change stream,
 * as it does for any other update.
 * An update that was committed but not applied here when the refurbish loaded
 * is in the database and in the rewritten documents;
 * an implementation that treats the revisions it wrote as already applied loses it,
 * because the pending change event is skipped.
 * <p>
 * Sequoia doesn't check revisions on INSERT and REPLACE events,
 * so it re-applies the state and masks the mistake;
 * that is why these tests target Pando.
 */
@ReplayLogsOnFailure
@InjectFields
class RefurbishLocalStateTest extends AbstractMongoDriverTest {
	private static final long EVENT_DELAY_MS = 1_000;

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				PandoFormat.withGraftPoints("/catalog"),
				PandoFormat.oneBigDocument()
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		);
	}

	@Test
	void refurbishIncludesUpdatesThisBoskHasNotAppliedYet(TestInfo testInfo) throws Exception {
		Bosk<TestEntity> writer = newBosk(testInfo, driverSettings, "writer");
		writer.driver().flush();
		Refs writerRefs = writer.buildReferences(Refs.class);
		Reference<String> writerRootString = writer.rootReference().then(String.class, "string");

		// The victim's change-event processing is delayed, so the writer's update is
		// still unapplied here when the refurbish loads it from the database.
		MongoDriverSettings victimSettings = driverSettings.toBuilder()
			.testing(MongoDriverSettings.Testing.builder().eventDelayMS(EVENT_DELAY_MS).build())
			.build();
		// Probes are captured when the driver is constructed, so set them first;
		// they read the victim through this reference once it exists.
		AtomicReference<Bosk<TestEntity>> victimRef = new AtomicReference<>();
		AtomicReference<Refs> victimRefsRef = new AtomicReference<>();
		AtomicReference<Catalog<TestEntity>> victimCatalogBefore = new AtomicReference<>();
		BlockingGate refurbishGate = new BlockingGate("the refurbish load");
		MainDriver.setProbes(TestProbes.noop()
			.withBeforeRefurbishLoad(() -> {
				refurbishGate.signal();
				refurbishGate.awaitRelease(Duration.ofSeconds(60));
			})
			.withBeforeRefurbishDelete(() -> {
				// Shows the refurbish loaded an update the victim had not applied,
				// rather than relying on the event delay being long enough.
				Bosk<TestEntity> v = victimRef.get();
				try (var _ = v.readSession()) {
					assertEquals(victimCatalogBefore.get(), victimRefsRef.get().catalog().value(),
						"The victim should not have applied the writer's update yet");
				}
			}));
		Bosk<TestEntity> victim;
		try {
			victim = newBosk(testInfo, victimSettings, "victim");
		} finally {
			MainDriver.resetProbes();
		}
		victimRef.set(victim);
		victim.driver().flush();
		Refs victimRefs = victim.buildReferences(Refs.class);
		victimRefsRef.set(victimRefs);
		try (var _ = victim.readSession()) {
			victimCatalogBefore.set(victimRefs.catalog().value());
		}
		AtomicReference<Throwable> refurbishFailure = new AtomicReference<>();
		Thread refurbisher = new Thread(() -> {
			try {
				victim.getDriver(MongoDriver.class).refurbish();
			} catch (Throwable e) {
				refurbishFailure.set(e);
			}
		});
		Catalog<TestEntity> newCatalog = Catalog.of(
			TestEntity.empty(entity123, writerRefs.childCatalog(entity123)).withString("changed by the writer"));
		try {
			refurbisher.start();
			refurbishGate.awaitSignal(Duration.ofSeconds(30));

			writer.driver().submitReplacement(writerRefs.catalog(), newCatalog);
			writer.driver().flush();
		} finally {
			refurbishGate.release();
			refurbisher.join(60_000);
		}
		assertFalse(refurbisher.isAlive(), "Refurbish should finish");
		assertNull(refurbishFailure.get(), "Refurbish should not fail");

		// Change events are ordered, so a marker update whose application the victim
		// waits for proves that the victim has processed every earlier event.
		writer.driver().submitReplacement(writerRootString, "marker");
		writer.driver().flush();
		victim.driver().flush();

		try (var _ = victim.readSession()) {
			assertEquals(newCatalog, victimRefs.catalog().value(),
				"The refurbishing bosk must include changes the refurbish loaded from the database");
		}
	}

	@Test
	void refurbishToAnotherFormat_reloadsWithTheNewFormat(TestInfo testInfo) throws Exception {
		// Start the collection in the other format, so the victim's preferred format
		// differs from what it loads.
		MongoDriverSettings settings = driverSettings;
		Bosk<TestEntity> otherFormat = newBosk(testInfo,
			settings.toBuilder()
				.preferredDatabaseFormat(differentFormat(settings.preferredDatabaseFormat()))
				.build(),
			"otherFormat");
		otherFormat.driver().flush();

		CountDownLatch reloaded = new CountDownLatch(1);
		AtomicInteger connections = new AtomicInteger(0);
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(listener -> new ForwardingChangeListener(listener) {
			@Override
			public void onConnectionSucceeded() throws UnrecognizedFormatException, InterruptedException, IOException, TimeoutException, InitialStateException, FailedMongoClientSessionException, InvalidCollectionContentsException {
				super.onConnectionSucceeded();
				if (connections.incrementAndGet() == 2) {
					reloaded.countDown();
				}
			}
		}));
		Bosk<TestEntity> victim;
		try {
			victim = newBosk(testInfo, settings, "victim");
		} finally {
			MainDriver.resetProbes();
		}
		Refs refs = victim.buildReferences(Refs.class);

		victim.getDriver(MongoDriver.class).refurbish();
		assertTrue(reloaded.await(30, SECONDS),
			"The bosk should reconnect and reload after the format changes");

		Reference<Catalog<TestEntity>> catalog = refs.catalog();
		victim.driver().submitReplacement(catalog, Catalog.empty());
		victim.driver().flush();
		try (var _ = victim.readSession()) {
			assertEquals(Catalog.empty(), catalog.value(),
				"The refurbished bosk should accept updates after it reconnects");
		}
	}

	private static MongoDriverSettings.DatabaseFormat differentFormat(MongoDriverSettings.DatabaseFormat current) {
		return current instanceof PandoFormat
			? MongoDriverSettings.DatabaseFormat.SEQUOIA
			: PandoFormat.withGraftPoints("/catalog");
	}

	private Bosk<TestEntity> newBosk(TestInfo testInfo, MongoDriverSettings settings, String name) {
		DriverFactory<TestEntity> factory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<TestEntity>factory(
				mongoService.clientSettings(testInfo),
				settings,
				new BsonSerializer())
				.build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return new Bosk<>(
			boskName(name),
			TestEntity.class,
			this::singleEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(factory).build());
	}

	private TestEntity singleEntryInitialState(Bosk<TestEntity> bosk) throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		return initialRootWithEmptyCatalog(bosk)
			.withCatalog(Catalog.of(TestEntity.empty(entity123, refs.childCatalog(entity123))));
	}
}
