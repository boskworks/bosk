package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * {@link FormatSpecificRecovery} covers disruptions that exercise format-specific
 * event handling and state layout, so it runs both for Sequoia (the whole state is one
 * document) and for a Pando format whose state is scattered across graft points.
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
			setLogging(ERROR, MainDriver.class, ChangeReceiver.class);
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

		void setRevision(long revisionNumber) {
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection())
				.updateOne(
					new BsonDocument(),
					new BsonDocument("$set", new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonInt64(revisionNumber))) // Value is ignored
				);
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
			T value;
			if (flushOrWait == FlushOrWait.FLUSH) {
				bosk.driver().flush();
				value = readUnderSession(bosk, actual);
			} else {
				long deadline = System.nanoTime() + WAIT_BUDGET.toNanos();
				long intervalNanos = WAIT_INITIAL_POLL.toNanos();
				value = readUnderSession(bosk, actual);
				while (!expected.equals(value)) {
					long remainingNanos = deadline - System.nanoTime();
					if (remainingNanos <= 0) {
						break;
					}
					Thread.sleep(Duration.ofNanos(Math.min(intervalNanos, remainingNanos)));
					intervalNanos = Math.min(intervalNanos * 2, WAIT_MAX_POLL.toNanos());
					value = readUnderSession(bosk, actual);
				}
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

		@Test
		void revisionFieldMissingAtStartup_failsAsInvalidContents(TestInfo testInfo) {
			// Initialize the database with content so the manifest and root document exist
			initializeDatabase("revision field missing");

			// Remove the revision field from the root document
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection(), BsonDocument.class)
				.updateMany(
					new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonDocument("$exists", BsonBoolean.TRUE)),
					new BsonDocument("$unset", new BsonDocument(Formatter.DocumentFields.revision.name(), BsonNull.VALUE)) // Value is ignored
				);

			// Starting a new Bosk in FAIL_FAST mode should throw because the database
			// contents are invalid (the root document lacks the revision field),
			// not because of a NullPointerException.
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

			InitialStateFailureException e = assertThrows(InitialStateFailureException.class, () -> new Bosk<>(
				boskName("revisionFieldMissing"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder()
					.driverFactory(failFastFactory)
					.build()
			));
			assertTrue(hasCause(e, InvalidCollectionContentsException.class),
				"Missing revision field should be reported as invalid collection contents, but was: " + e);
			assertFalse(hasCause(e, NullPointerException.class),
				"Missing revision field must not cause a NullPointerException");
		}
	}

	@Nested
	@InjectFields
	@InjectFrom({FlushOrWait.class})
	class FormatSpecificRecovery extends DisruptionRecoveryTestBase {
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
			BsonDocument rootDocumentsFilter = new BsonDocument("path", new BsonString("/"));
			testRecovery(() -> {
				LOGGER.debug("Save original document");
				try (var cursor = collection.find(rootDocumentsFilter).cursor()) {
					originalDocument.set(cursor.next());
				}
				LOGGER.debug("Delete document");
				collection.deleteMany(rootDocumentsFilter);
			}, (b) -> {
				LOGGER.debug("Restore original document");
				// NOTE: This doesn't actually work cleanly with Pando, because restoring the root document by itself
				// doesn't cause all the subparts to appear in the change stream, which means there's not enough
				// info to reassemble the whole state tree. It ends up failing and reinitializing, so it passes the test.
				collection.insertOne(originalDocument.get());
				return b;
			});
		}

		@Test
		@Disabled
		void revisionDeleted_recovers() throws InterruptedException, IOException {
			// It's not clear that this is a valid test. If this test is a burden to support,
			// we can consider removing it.
			//
			// In general, changing the revision field to a lower number is not fair to bosk
			// unless you also revert to the corresponding state. (And deleting the revision
			// field is conceptually equivalent to setting it to zero.) Deleting the revision field
			// is a special case because no ordinary bosk operations delete the revision field, or
			// set it to zero, so it's not unreasonable to expect bosk to handle this; but it's
			// also not reasonable to be surprised if it didn't.
			LOGGER.debug("Setup database to beforeState");
			TestEntity beforeState = initializeDatabase("before deletion");

			Bosk<TestEntity> bosk = new Bosk<>(boskName(getClass().getSimpleName()), TestEntity.class, AbstractMongoDriverTest::initialState, BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

			try (var _ = bosk.readSession()) {
				// This can fail on very short timescales; see testRecovery.
				// This isn't related to the revision deletion issues described above
				// and shouldn't really be grounds for removing this test.
				assertEquals(beforeState, bosk.rootReference().value());
			}

			LOGGER.debug("Delete revision field");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection())
				.updateOne(
					new BsonDocument(),
					new BsonDocument("$unset", new BsonDocument(Formatter.DocumentFields.revision.name(), BsonNull.VALUE)) // Value is ignored
				);

			LOGGER.debug("Ensure the driver recovers");
			assertEventuallyEquals(bosk, beforeState, () -> bosk.rootReference().value());

			LOGGER.debug("Repair by setting revision in the far future");
			setRevision(1000L);

			LOGGER.debug("Ensure the driver recovers again");
			assertEventuallyEquals(bosk, beforeState, () -> bosk.rootReference().value());
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

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeClass) {
		for (Throwable t = throwable; t != null; t = t.getCause()) {
			if (causeClass.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * How long a WAIT-mode test gives the driver to converge on its own.
	 * This is comfortably longer than a reconnection-and-reload cycle, and it
	 * returns as soon as the state converges.
	 */
	private static final Duration WAIT_BUDGET = Duration.ofMillis(20L * SHORT_TIMESCALE);
	private static final Duration WAIT_INITIAL_POLL = Duration.ofMillis(10);
	private static final Duration WAIT_MAX_POLL = Duration.ofMillis(100);

	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryTest.class);
}
