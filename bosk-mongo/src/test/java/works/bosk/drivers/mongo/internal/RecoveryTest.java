package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskDriver;
import works.bosk.Catalog;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.exceptions.InitialStateFailureException;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.BoskLogFilter;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.slf4j.event.Level.WARN;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.drivers.mongo.internal.MongoService.FailureMode.CLOSE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the kinds of recovery actions a human operator might take to try to get a busted service running again.
 * <p>
 * The disruptions are split into groups.
 * {@link FormatAgnosticRecovery} covers disruptions whose detection and recovery
 * do not depend on how the state is laid out in the database, so one format suffices.
 * {@link DatabaseInterventionRecovery} covers disruptions that exercise
 * format-specific event handling and state layout, so it runs for both formats.
 * {@link WriteIdRoleRecovery} covers Pando's writeId role validation.
 * {@link StartupFailures} covers starting up against a damaged collection.
 * <p>
 * The disruption-recovery tests run in both {@link FlushOrWait} modes:
 * an application that never calls {@code flush} must still notice and recover.
 */
class RecoveryTest {

	abstract static class RecoveryTestBase extends AbstractMongoDriverTest {
		ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

		@BeforeEach
		void overrideLogging() {
			// These tests deliberately provoke a lot of warnings, so log errors only
			suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);
		}

		@BeforeEach
		void setupErrorRecording() {
			errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
			MainDriver.setProbes(TestProbes.noop()
				.withListenerFactory(d -> new ErrorRecordingChangeListener(errorRecorder, d))
				.withFailOnDisruption());
		}

		@AfterEach
		void resetErrorRecording() {
			MainDriver.resetProbes();
		}

		/**
		 * Creates a bosk that writes the given distinctive state to the database, waits
		 * for the write to land, and closes it. This is setup rather than a liveness
		 * assertion, so it always flushes.
		 */
		TestEntity initializeDatabase(String distinctiveString) {
			try {
				AtomicReference<MongoDriver> driverRef = new AtomicReference<>();
				Bosk<TestEntity> prepBosk = new Bosk<>(
					boskName("Prep " + getClass().getSimpleName()),
					TestEntity.class,
					bosk -> initialRootWithNestedSideTable(bosk).withString(distinctiveString),
					BoskConfig.<TestEntity>builder().driverFactory((b, d) -> {
						var mongoDriver = (MongoDriver) driverFactory.build(b, d);
						driverRef.set(mongoDriver);
						return mongoDriver;
					}).build());
				var driver = driverRef.get();
				driver.flush();
				driver.close();

				return initialRootWithNestedSideTable(prepBosk).withString(distinctiveString);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}

		/**
		 * The recovery scenarios scatter and gather the whole state tree, so give the
		 * initial state content at the nested graft points; otherwise a recovery bug
		 * in the deep scatter would go unexercised.
		 */
		private static TestEntity initialRootWithNestedSideTable(Bosk<TestEntity> bosk) throws InvalidTypeException {
			Refs refs = bosk.buildReferences(Refs.class);
			Reference<Catalog<TestEntity>> entry123Catalog = refs.childCatalog(entity123);
			Identifier s1ID = Identifier.from("s1");
			TestEntity entry123 = TestEntity.empty(entity123, entry123Catalog)
				.withSideTable(SideTable.of(
					entry123Catalog,
					s1ID,
					TestEntity.empty(s1ID, entry123Catalog)
						.withString("nested")));
			return initialRoot(bosk).withCatalog(Catalog.of(
				entry123,
				TestEntity.empty(entity124, refs.childCatalog(entity124))
			));
		}
	}

	/**
	 * Base for the disruption-recovery tests, which run once per {@link FlushOrWait} mode.
	 */
	abstract static class DisruptionRecoveryTestBase extends RecoveryTestBase {
		@Injected FlushOrWait flushOrWait;

		/**
		 * Asserts that {@code actual} eventually equals {@code expected}.
		 * In FLUSH mode, the driver is flushed and {@code actual} is evaluated once.
		 * In WAIT mode, it is evaluated repeatedly with exponential backoff
		 * until it matches or the {@link #WAIT_BUDGET} elapses,
		 * so the test asserts that the driver eventually recovers on its own
		 * rather than sleeping for a fixed time and hoping.
		 * <p>
		 * {@code actual} is evaluated with a read session open on the current thread.
		 */
		<T> void assertEventuallyEquals(Bosk<?> bosk, T expected, Supplier<T> actual) throws IOException, InterruptedException {
			if (flushOrWait == FlushOrWait.FLUSH) {
				bosk.driver().flush();
				assertEquals(expected, readUnderSession(bosk, actual));
			} else {
				assertEventuallyRecovers(bosk, expected, actual);
			}
		}

		/**
		 * Asserts that {@code actual} eventually equals {@code expected} without
		 * flushing, in both {@link FlushOrWait} modes.
		 * <p>
		 * Use this for changes a flush does not promise to observe, such as a
		 * write made directly to the database that the receiver rejects and
		 * reloads.
		 */
		<T> void assertEventuallyRecovers(Bosk<?> bosk, T expected, Supplier<T> actual) throws IOException, InterruptedException {
			long deadline = System.nanoTime() + WAIT_BUDGET.toNanos();
			long intervalNanos = WAIT_INITIAL_POLL.toNanos();
			T value = readUnderSession(bosk, actual);
			while (!expected.equals(value)) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0) {
					break;
				}
				Thread.sleep(Duration.ofNanos(Math.min(intervalNanos, remainingNanos)));
				intervalNanos = Math.min(intervalNanos * 2, WAIT_MAX_POLL.toNanos());
				value = readUnderSession(bosk, actual);
			}
			assertEquals(expected, value);
		}

		private static <T> T readUnderSession(Bosk<?> bosk, Supplier<T> actual) {
			try (var _ = bosk.readSession()) {
				return actual.get();
			}
		}

		void testRecovery(Runnable disruptiveAction, Function<TestEntity, TestEntity> recoveryAction) throws IOException, InterruptedException {
			LOGGER.debug("Setup database to beforeState");
			TestEntity beforeState = initializeDatabase("before disruption");

			Bosk<TestEntity> bosk = new Bosk<>(boskName(getClass().getSimpleName()), TestEntity.class, AbstractMongoDriverTest::initialState, BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

			try (var _ = bosk.readSession()) {
				// Note: with very short timescales, this assertion can fail because the newly created bosk
				// times out trying to read the database contents and instead uses AbstractMongoDriverTest::initialState.
				// This is actually valid behaviour for a sufficiently impatient user.
				assertEquals(beforeState, bosk.rootReference().value());
			}

			errorRecorder.assertAllClear("before disruption");
			LOGGER.debug("Run disruptive action");
			disruptiveAction.run();

			LOGGER.debug("Ensure flush throws");
			assertThrows(FlushFailureException.class, () -> bosk.driver().flush());
			try (var _ = bosk.readSession()) {
				assertEquals(beforeState, bosk.rootReference().value());
			}

			LOGGER.debug("Run recovery action");
			TestEntity afterState = recoveryAction.apply(beforeState);

			LOGGER.debug("Ensure the driver recovers");
			assertEventuallyEquals(bosk, afterState, () -> bosk.rootReference().value());

			LOGGER.debug("Ensure the driver accepts updates again");
			TestEntity updated = afterState.withString("after recovery");
			bosk.driver().submitReplacement(bosk.rootReference(), updated);
			assertEventuallyEquals(bosk, updated, () -> bosk.rootReference().value());
		}
	}

	@Nested
	@InjectFields
	@InjectFrom({FlushOrWait.class})
	class FormatAgnosticRecovery extends DisruptionRecoveryTestBase {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return TestParameters.driverSettings(
				Stream.of(MongoDriverSettings.DatabaseFormat.SEQUOIA),
				Stream.of(TestParameters.EventTiming.NORMAL)
			).map(b -> b.applyDriverSettings(s -> s
				.timescaleMS(SHORT_TIMESCALE)
			));
		}

		@Test
		@DisruptsMongoProxy
		void initialOutage_recovers() throws InvalidTypeException, InterruptedException, IOException {
			LOGGER.debug("Set up the database contents to be different from initialState");
			TestEntity initialState = initializeDatabase("distinctive string");

			LOGGER.debug("Cut mongo connection");
			mongoService.disruptConnection(CLOSE);
			tearDownActions.add(()->mongoService.restoreConnection(CLOSE));

			LOGGER.debug("Create a new bosk that can't connect");
			Bosk<TestEntity> bosk = new Bosk<>(getClass().getSimpleName() + boskCounter.incrementAndGet(), TestEntity.class, AbstractMongoDriverTest::initialState, BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			LOGGER.debug("Done creating bosk");

			Refs refs = bosk.buildReferences(Refs.class);
			BoskDriver driver = bosk.driver();
			TestEntity defaultRoot = initialRoot(bosk);

			try (var _ = bosk.readSession()) {
				assertEquals(defaultRoot, bosk.rootReference().value(),
					"Uses default state if database is unavailable");
			}

			LOGGER.debug("Verify that driver operations throw");
			assertThrows(FlushFailureException.class, driver::flush,
				"Flush disallowed during outage");
			assertThrows(Exception.class, () -> driver.submitReplacement(bosk.rootReference(), defaultRoot),
				"Updates disallowed during outage");

			LOGGER.debug("Restore mongo connection");
			mongoService.restoreConnection(CLOSE);

			LOGGER.debug("Wait and check that the state updates");
			assertEventuallyEquals(bosk, initialState, () -> bosk.rootReference().value());

			LOGGER.debug("Make a change to the bosk and verify that it gets through");
			driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
			TestEntity expected = initialState
				.withListing(Listing.of(refs.catalog(), entity123));

			assertEventuallyEquals(bosk, expected, () -> bosk.rootReference().value());
		}

		@Test
		void databaseDropped_recovers() throws InterruptedException, IOException {
			testRecovery(() -> {
				LOGGER.debug("Drop database");
				mongoService.client()
					.getDatabase(driverSettings.database())
					.drop();
			}, (_) -> initializeDatabase("after drop"));
		}
	}

	/**
	 * Tests that starting up against a damaged collection fails cleanly.
	 * These don't depend on {@link FlushOrWait}, so they run once rather than once per mode.
	 */
	@Nested
	@InjectFields
	class StartupFailures extends RecoveryTestBase {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return TestParameters.driverSettings(
				Stream.of(MongoDriverSettings.DatabaseFormat.SEQUOIA),
				Stream.of(TestParameters.EventTiming.NORMAL)
			).map(b -> b.applyDriverSettings(s -> s
				.timescaleMS(SHORT_TIMESCALE)
			));
		}

		@Test
		void stateDocumentDeletedBeforeStartup_failsOnInitialize(TestInfo testInfo) {
			// Initialize the database with content that differs from initialState
			initializeDatabase("state document deleted");

			// Delete all non-manifest documents, simulating a state document getting lost
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection(), BsonDocument.class)
				.deleteMany(new BsonDocument("_id", new BsonDocument("$ne", MANIFEST_ID)));

			// Starting a new Bosk in FAIL_FAST mode should throw because the
			// manifest exists but the state document is missing
			MongoDriverSettings failFastSettings = driverSettings.toBuilder()
				.initialDatabaseUnavailableMode(InitialDatabaseUnavailableMode.FAIL_FAST)
				.build();
			DriverFactory<TestEntity> failFastFactory = DriverStack.of(
				BoskLogFilter.withController(logController),
				(info, downstream) ->
					MongoDriver.<TestEntity>factory(
						mongoService.clientSettings(testInfo),
						failFastSettings,
						new BsonSerializer()
					).build(info, downstream)
			);

			assertThrows(InitialStateFailureException.class, () -> new Bosk<>(
				boskName("stateDocDeleted"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder()
					.driverFactory(failFastFactory)
					.build()
			));
		}

	}

	@Nested
	@InjectFields
	@InjectFrom({FlushOrWait.class})
	class DatabaseInterventionRecovery extends DisruptionRecoveryTestBase {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return TestParameters.driverSettings(
				Stream.of(
					MongoDriverSettings.DatabaseFormat.SEQUOIA,
					PandoFormat.withGraftPoints("/catalog", "/sideTable")
				),
				Stream.of(TestParameters.EventTiming.NORMAL)
			).map(b -> b.applyDriverSettings(s -> s
				.timescaleMS(SHORT_TIMESCALE)
			));
		}

		@Test
		void collectionDropped_recovers() throws InterruptedException, IOException {
			testRecovery(() -> {
				LOGGER.debug("Drop collection");
				mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(driverSettings.collection())
					.drop();
			}, (_) -> initializeDatabase("after drop"));
		}

		/**
		 * Deleting the root state document leaves the collection invalid.
		 * Outside a refurbish, the driver never deletes it, so the deletion
		 * signals that the state is being replaced. Both formats disconnect on
		 * the DELETE itself rather than waiting for a flush to find it missing.
		 */
		@Test
		void rootDocumentDeleted_reconnectsAndReloads() throws InterruptedException, IOException {
			TestEntity before = initializeDatabase("before root deletion");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName(getClass().getSimpleName()),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			assertEventuallyEquals(bosk, before, () -> bosk.rootReference().value());

			LOGGER.debug("Delete the root state document");
			MongoCollection<BsonDocument> collection = mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection(), BsonDocument.class);
			collection.deleteMany(rootDocumentsFilter());

			LOGGER.debug("Rebuild the collection so the driver can reload");
			collection.deleteMany(new BsonDocument());
			TestEntity after = initializeDatabase("after root deletion");
			assertEventuallyEquals(bosk, after, () -> bosk.rootReference().value());

			assertTrue(errorRecorder.disconnections.stream()
					.anyMatch(e -> e instanceof CollectionRewrittenException
						&& String.valueOf(e.getMessage()).contains("Root document has been deleted")),
				"Deleting the root state document must make the receiver reload: " + errorRecorder);
		}

		@Test
		void documentDeleted_recovers() throws InterruptedException, IOException {
			testRecovery(() -> {
				LOGGER.debug("Delete document");
				mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(driverSettings.collection())
					.deleteMany(new BsonDocument());
			}, (_) -> initializeDatabase("after deletion"));
		}

		@Test
		void documentReappears_recovers() throws InterruptedException, IOException {
			MongoCollection<Document> collection = mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection());
			AtomicReference<Document> originalDocument = new AtomicReference<>();
			BsonDocument rootFilter = rootDocumentsFilter();
			testRecovery(() -> {
				LOGGER.debug("Save original document");
				try (var cursor = collection.find(rootFilter).cursor()) {
					originalDocument.set(cursor.next());
				}
				LOGGER.debug("Delete document");
				collection.deleteMany(rootFilter);
			}, (b) -> {
				LOGGER.debug("Restore original document");
				// NOTE: This doesn't actually work cleanly with Pando, because restoring the root document by itself
				// doesn't cause all the subparts to appear in the change stream, which means there's not enough
				// info to reassemble the whole state tree. It ends up failing and reinitializing, so it passes the test.
				collection.insertOne(originalDocument.get());
				return b;
			});
		}

		/**
		 * An operator editing the state directly does not update any metadata.
		 * The change is still an ordinary change event at a later cluster time,
		 * so the driver applies it without needing a version bump. A direct
		 * database write is outside the flush guarantee, so poll for it.
		 */
		@Test
		void operatorEditWithoutMetadataChange_isApplied() throws InterruptedException, IOException {
			TestEntity before = initializeDatabase("before operator edit");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName(getClass().getSimpleName()),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			assertEventuallyEquals(bosk, before, () -> bosk.rootReference().value());

			LOGGER.debug("Edit state.string directly, leaving epoch, lockId, and writeId alone");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection())
				.updateOne(rootDocumentsFilter(),
					new BsonDocument("$set", new BsonDocument("state.string", new BsonString("edited by operator"))));

			assertEventuallyRecovers(bosk, before.withString("edited by operator"),
				() -> bosk.rootReference().value());
		}
	}

	@Nested
	@InjectFields
	@InjectFrom({FlushOrWait.class})
	class WriteIdRoleRecovery extends DisruptionRecoveryTestBase {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			// Only Pando validates writeId roles. Sequoia applies the update
			// directly, so it would satisfy this test without the validation.
			return TestParameters.driverSettings(
				Stream.of(PandoFormat.withGraftPoints("/catalog", "/sideTable")),
				Stream.of(TestParameters.EventTiming.NORMAL)
			).map(b -> b.applyDriverSettings(s -> s
				.timescaleMS(SHORT_TIMESCALE)
			));
		}

		/**
		 * A hand-written transaction whose update has no {@code writeId} is not a
		 * valid state write. That is an external database update, so a flush does
		 * not promise to observe it: the receiver eventually rejects the write and
		 * reloads, which is how the edit becomes visible. Poll in both
		 * {@link FlushOrWait} modes, which exercises the role validation.
		 */
		@Test
		void transactionalEditWithoutWriteId_reconnectsAndReloads() throws Exception {
			TestEntity before = initializeDatabase("before transactional edit");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName(getClass().getSimpleName()),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			assertEventuallyEquals(bosk, before, () -> bosk.rootReference().value());

			LOGGER.debug("Edit state.string in a hand-written transaction with no writeId");
			try (var session = mongoService.client().startSession()) {
				session.startTransaction();
				mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(driverSettings.collection())
					.updateOne(session, rootDocumentsFilter(),
						new BsonDocument("$set", new BsonDocument("state.string", new BsonString("edited in a transaction"))));
				session.commitTransaction();
			}

			assertEventuallyRecovers(bosk, before.withString("edited in a transaction"),
				() -> bosk.rootReference().value());
		}

	}

	enum FlushOrWait {
		FLUSH,

		/**
		 * Technically, these tests should be using {@link BoskDriver#flush()},
		 * but we also want to exhibit some "liveness" so that users who don't
		 * call {@code flush} eventually see updates anyway.
		 * <p>
		 * This test mode waits for the state to converge without flushing, so it
		 * fails if the driver needs a flush to make progress.
		 */
		WAIT,
	}

	/**
	 * How long a test that polls gives the driver to converge on its own.
	 * This is comfortably longer than a reconnection-and-reload cycle, and it
	 * returns as soon as the state converges.
	 */
	private static final Duration WAIT_BUDGET = Duration.ofMillis(20L * SHORT_TIMESCALE);
	private static final Duration WAIT_INITIAL_POLL = Duration.ofMillis(10);
	private static final Duration WAIT_MAX_POLL = Duration.ofMillis(100);

	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryTest.class);
}
