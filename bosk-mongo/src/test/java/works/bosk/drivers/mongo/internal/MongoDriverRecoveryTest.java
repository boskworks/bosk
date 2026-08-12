package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import works.bosk.testing.junit.Slow;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the kinds of recovery actions a human operator might take to try to get a busted service running again.
 */
@Slow
@InjectFields
@InjectFrom({MongoDriverRecoveryTest.FlushOrWait.class})
public class MongoDriverRecoveryTest extends AbstractMongoDriverTest {
	@Injected FlushOrWait flushOrWait;
	ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

	@BeforeEach
	void overrideLogging() {
		// This test deliberately provokes a lot of warnings, so log errors only
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);
	}

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(d -> new ErrorRecordingChangeListener(errorRecorder, d)));
	}

	@AfterEach
	void resetErrorRecording() {
		MainDriver.TEST_PROBES.remove();
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				MongoDriverSettings.DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable"),
				PandoFormat.withGraftPoints("/catalog/-x-/sideTable")
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s
			.timescaleMS(SHORT_TIMESCALE) // Note that some tests can take as long as 25x this
		));
	}

	enum FlushOrWait {
		FLUSH,

		/**
		 * Technically, these tests should be using {@link BoskDriver#flush()},
		 * but we also want to exhibit some "liveness" so that users who don't
		 * call {@code flush} eventually see updates anyway.
		 * <p>
		 * This test mode inserts a delay instead of {@code flush} to ensure
		 * updates eventually arrive.
		 */
		WAIT,
	}


	@Test
	@DisruptsMongoProxy
	void initialOutage_recovers() throws InvalidTypeException, InterruptedException, IOException {
		LOGGER.debug("Set up the database contents to be different from initialState");
		TestEntity initialState = initializeDatabase("distinctive string");

		LOGGER.debug("Cut mongo connection");
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());

		LOGGER.debug("Create a new bosk that can't connect");
		Bosk<TestEntity> bosk = new Bosk<>(getClass().getSimpleName() + boskCounter.incrementAndGet(), TestEntity.class, AbstractMongoDriverTest::initialState, BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		LOGGER.debug("Done creating bosk");

		MongoDriverSpecialTest.Refs refs = bosk.buildReferences(MongoDriverSpecialTest.Refs.class);
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
		mongoService.restoreConnection();

		LOGGER.debug("Wait and check that the state updates");
		// With FLUSH this succeeds almost immediately.
		// With WAIT, it is artificially delayed.
		waitFor(driver);
		try (var _ = bosk.readSession()) {
			assertEquals(initialState, bosk.rootReference().value(),
				"Updates to database state once it reconnects");
		}

		LOGGER.debug("Make a change to the bosk and verify that it gets through");
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialState
			.withListing(Listing.of(refs.catalog(), entity123));

		waitFor(driver);
		try (@SuppressWarnings("unused") Bosk<?>.ReadSession readSession = bosk.readSession()) {
			assertEquals(expected, bosk.rootReference().value());
		}
	}

	private void waitFor(BoskDriver driver) throws IOException, InterruptedException {
		switch (flushOrWait) {
			case FLUSH:
				driver.flush();
				break;
			case WAIT:
				// The user really has no business expecting updates to occur promptly.
				//
				// This is used in two circumstances:
				// 1. If the operation is expected to succeed, then the worst-case
				// delay is the delay time for the connection loop, plus the time
				// to detect and publish the new format driver,
				// plus the time to execute the operation itself.
				// 4*timescaleMS ought to be plenty for all that.
				// 2. If the operation is expected to fail, no amount of waiting
				// will make it succeed, so we can ignore this case.
				//
				long sleepTime = 4L * driverSettings.timescaleMS();
				LOGGER.debug("Waiting for {} ms", sleepTime);
				Thread.sleep(sleepTime);
				LOGGER.debug("...done waiting");
				break;
		}
	}

	@Test
	void databaseDropped_recovers() throws InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop database");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (_) -> initializeDatabase("after drop"));
	}

	@Test
	void collectionDropped_recovers() throws InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop collection");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (_) -> initializeDatabase("after drop"));
	}

	@Test
	void documentDeleted_recovers() throws InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Delete document");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.deleteMany(new BsonDocument());
		}, (_) -> initializeDatabase("after deletion"));
	}

	@Test
	void documentReappears_recovers() throws InterruptedException, IOException {
		MongoCollection<Document> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME);
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
			.getCollection(COLLECTION_NAME)
			.updateOne(
				new BsonDocument(),
				new BsonDocument("$unset", new BsonDocument(Formatter.DocumentFields.revision.name(), BsonNull.VALUE)) // Value is ignored
			);

		LOGGER.debug("Ensure flush works");
		waitFor(bosk.driver());
		try (var _ = bosk.readSession()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Repair by setting revision in the far future");
		setRevision(1000L);

		LOGGER.debug("Ensure flush works again");
		waitFor(bosk.driver());
		try (var _ = bosk.readSession()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}
	}

	@Test
	void stateDocumentDeletedBeforeStartup_failsOnInitialize(TestInfo testInfo) {
		// Initialize the database with content that differs from initialState
		TestEntity beforeState = initializeDatabase("state document deleted");

		// Delete all non-manifest documents, simulating a state document getting lost
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME, BsonDocument.class)
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
			.getCollection(COLLECTION_NAME, BsonDocument.class)
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

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeClass) {
		for (Throwable t = throwable; t != null; t = t.getCause()) {
			if (causeClass.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

	private void setRevision(long revisionNumber) {
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME)
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

	private TestEntity initializeDatabase(String distinctiveString) {
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
			waitFor(driver);
			driver.close();

			return initialRootWithNestedSideTable(prepBosk).withString(distinctiveString);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private void testRecovery(Runnable disruptiveAction, Function<TestEntity, TestEntity> recoveryAction) throws IOException, InterruptedException {
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

		LOGGER.debug("Ensure flush works");
		waitFor(bosk.driver());
		try (var _ = bosk.readSession()) {
			assertEquals(afterState, bosk.rootReference().value());
		}
	}

	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverRecoveryTest.class);
}
