package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
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
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.entity123;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.entity124;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.rootID;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * An update must never be accepted and then lost. If the collection is
 * refurbished to a different format while a driver is still running, that
 * driver's updates target documents that no longer exist, and accepting them
 * silently would tell the caller something happened when nothing did.
 * <p>
 * The race is made deterministic by blocking the victim's change-event
 * processing: its format driver cannot learn about the refurbish until the
 * test releases the gate, so the update is guaranteed to be planned against
 * the old format. The intended remedy is for the driver to reconnect and
 * re-plan the update, so the test asserts that the update takes effect rather
 * than asserting anything about the failure that provokes the reconnect.
 * <p>
 * Every kind of update is covered, because every kind can lose a race with a
 * format change, including the conditional ones whose writes normally apply
 * nothing.
 */
@ReplayLogsOnFailure
@InjectFields
class RefurbishUpdateLossTest extends AbstractMongoDriverTest {
	private static final Identifier entity125 = Identifier.from("125");

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog")
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s.timescaleMS(LONG_TIMESCALE)));
	}

	@Test
	void replacementConcurrentWithFormatChange_isApplied(TestInfo testInfo) throws Exception {
		MongoDriverSettings refurbished = refurbishedSettings();
		runUpdateConcurrentWithFormatChange(testInfo, refurbished, (victim, refs) ->
			victim.driver().submitReplacement(stringOf(refs, entity123), "must not be lost"));

		Bosk<TestEntity> verifier = newBosk(testInfo, refurbished, "verifier");
		Refs verifierRefs = verifier.buildReferences(Refs.class);
		verifier.driver().flush();
		try (var _ = verifier.readSession()) {
			assertEquals("must not be lost", stringOf(verifierRefs, entity123).value(),
				"An update concurrent with a format change must be applied, not lost");
		}
	}

	@Test
	void conditionalReplacementConcurrentWithFormatChange_isApplied(TestInfo testInfo) throws Exception {
		MongoDriverSettings refurbished = refurbishedSettings();
		runUpdateConcurrentWithFormatChange(testInfo, refurbished, (victim, refs) ->
			victim.driver().submitConditionalReplacement(stringOf(refs, entity123), "must not be lost",
				refs.catalog().then(entity123).then(Identifier.class, "id"), entity123));

		Bosk<TestEntity> verifier = newBosk(testInfo, refurbished, "verifier");
		Refs verifierRefs = verifier.buildReferences(Refs.class);
		verifier.driver().flush();
		try (var _ = verifier.readSession()) {
			assertEquals("must not be lost", stringOf(verifierRefs, entity123).value(),
				"A conditional update concurrent with a format change must be applied, not lost");
		}
	}

	@Test
	void conditionalCreationConcurrentWithFormatChange_isApplied(TestInfo testInfo) throws Exception {
		MongoDriverSettings refurbished = refurbishedSettings();
		runUpdateConcurrentWithFormatChange(testInfo, refurbished, (victim, refs) ->
			victim.driver().submitConditionalCreation(
				refs.catalog().then(entity125),
				TestEntity.empty(entity125, refs.childCatalog(entity125))));

		Bosk<TestEntity> verifier = newBosk(testInfo, refurbished, "verifier");
		Refs verifierRefs = verifier.buildReferences(Refs.class);
		verifier.driver().flush();
		try (var _ = verifier.readSession()) {
			assertEquals(entity125.toString(), stringOf(verifierRefs, entity125).value(),
				"A conditional creation concurrent with a format change must be applied, not lost");
		}
	}

	@Test
	void conditionalDeletionConcurrentWithFormatChange_isApplied(TestInfo testInfo) throws Exception {
		MongoDriverSettings refurbished = refurbishedSettings();
		runUpdateConcurrentWithFormatChange(testInfo, refurbished, (victim, refs) -> {
			// The root's id is in every layout's topmost document, so this
			// deletion's precondition sits alongside the write in all formats.
			Reference<Identifier> rootIdRef = victim.rootReference().then(Identifier.class, "id");
			victim.driver().submitConditionalDeletion(refs.catalog().then(entity124), rootIdRef, rootID);
		});

		Bosk<TestEntity> verifier = newBosk(testInfo, refurbished, "verifier");
		Refs verifierRefs = verifier.buildReferences(Refs.class);
		verifier.driver().flush();
		try (var _ = verifier.readSession()) {
			assertEquals(Set.of(entity123), verifierRefs.catalog().value().asMap().keySet(),
				"A conditional deletion concurrent with a format change must be applied, not lost");
		}
	}

	private Reference<String> stringOf(Refs refs, Identifier entity) throws InvalidTypeException {
		return refs.catalog().then(entity).then(String.class, "string");
	}

	private MongoDriverSettings refurbishedSettings() {
		return driverSettings.toBuilder()
			.preferredDatabaseFormat(differentFormat(driverSettings.preferredDatabaseFormat()))
			.build();
	}

	/**
	 * Refurbishes the collection to a different format while {@code update} runs
	 * from another thread, and fails if the update reports a failure.
	 */
	private void runUpdateConcurrentWithFormatChange(
		TestInfo testInfo,
		MongoDriverSettings refurbishedSettings,
		UpdateAction update
	) throws Exception {
		BlockingGate eventGate = new BlockingGate("the victim bosk's change events");
		CountDownLatch writeAttempted = new CountDownLatch(1);
		AtomicBoolean interceptWrites = new AtomicBoolean(false);

		Bosk<TestEntity> victim = newVictimBosk(testInfo, eventGate, writeAttempted, interceptWrites);
		Refs victimRefs = victim.buildReferences(Refs.class);
		AtomicReference<Throwable> updateFailure = new AtomicReference<>();

		try {
			newBosk(testInfo, refurbishedSettings, "refurbisher").getDriver(MongoDriver.class).refurbish();

			// Submit from another thread: the update is expected to block while the
			// driver reconnects, which cannot happen until we release the gate below.
			interceptWrites.set(true);
			Thread updater = new Thread(() -> {
				try {
					update.run(victim, victimRefs);
				} catch (Throwable e) {
					updateFailure.set(e);
				}
			});
			updater.start();

			assertTrue(writeAttempted.await(30, SECONDS), "The update should reach the database");
			eventGate.release();
			updater.join(60_000);
			assertFalse(updater.isAlive(), "The update should finish once the driver has reconnected");
		} finally {
			eventGate.release();
		}

		assertNull(updateFailure.get(), "The update should not fail: the driver reconnects and retries it");
	}

	@FunctionalInterface
	private interface UpdateAction {
		void run(Bosk<TestEntity> victim, Refs refs) throws Exception;
	}

	private Bosk<TestEntity> newVictimBosk(
		TestInfo testInfo,
		BlockingGate eventGate,
		CountDownLatch writeAttempted,
		AtomicBoolean interceptWrites
	) {
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(listener ->
				new ForwardingChangeListener(listener) {
					@Override
					public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
						eventGate.awaitRelease(Duration.ofMinutes(5));
						super.onEvent(event);
					}
				})
			.withWriteInterceptor(filter -> {
				if (interceptWrites.get()) {
					writeAttempted.countDown();
				}
			}));
		try {
			return newBosk(testInfo, driverSettings, "victim");
		} finally {
			MainDriver.resetProbes();
		}
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
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(factory).build());
	}

	private static DatabaseFormat differentFormat(DatabaseFormat current) {
		if (current instanceof PandoFormat pando && !pando.graftPoints().isEmpty()) {
			return DatabaseFormat.SEQUOIA;
		} else {
			return PandoFormat.withGraftPoints("/catalog");
		}
	}

}
