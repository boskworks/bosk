package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.Listing;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.ParameterInjector;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.junit.Slow;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the kinds of recovery actions a human operator might take to try to get a busted service running again.
 */
@Slow
@InjectFrom({
	MongoDriverRecoveryTest.FlushOrWaitInjector.class,
	MongoDriverRecoveryTest.TestParameterInjector.class
})
public class MongoDriverRecoveryTest extends AbstractMongoDriverTest {
	FlushOrWait flushOrWait;

	@BeforeEach
	void overrideLogging() {
		// This test deliberately provokes a lot of warnings, so log errors only
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);
	}

	MongoDriverRecoveryTest(FlushOrWait flushOrWait, TestParameters.ParameterSet parameters) {
		super(parameters.driverSettingsBuilder());
		this.flushOrWait = flushOrWait;
	}

	record TestParameterInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == TestParameters.ParameterSet.class;
		}

		@Override
		public List<Object> values() {
			return TestParameters.driverSettings(
				Stream.of(
					MongoDriverSettings.DatabaseFormat.SEQUOIA,
					PandoFormat.oneBigDocument(),
					PandoFormat.withGraftPoints("/catalog", "/sideTable")
				),
				Stream.of(TestParameters.EventTiming.NORMAL)
			).map(b -> b.applyDriverSettings(s -> s
				.timescaleMS(SHORT_TIMESCALE) // Note that some tests can take as long as 25x this
			)).map(x -> (Object)x)
			.toList();
		}
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

	record FlushOrWaitInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == FlushOrWait.class;
		}

		@Override
		public List<Object> values() {
			return List.of((Object[])FlushOrWait.values());
		}
	}


	@InjectedTest
	@DisruptsMongoProxy
	void initialOutage_recovers() throws InvalidTypeException, InterruptedException, IOException {
		LOGGER.debug("Set up the database contents to be different from initialRoot");
		TestEntity initialState = initializeDatabase("distinctive string");

		LOGGER.debug("Cut mongo connection");
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());

		LOGGER.debug("Create a new bosk that can't connect");
		Bosk<TestEntity> bosk = new Bosk<>(getClass().getSimpleName() + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory, Bosk.simpleRegistrar());

		MongoDriverSpecialTest.Refs refs = bosk.buildReferences(MongoDriverSpecialTest.Refs.class);
		BoskDriver driver = bosk.driver();
		TestEntity defaultState = initialRoot(bosk);

		try (var _ = bosk.readContext()) {
			assertEquals(defaultState, bosk.rootReference().value(),
				"Uses default state if database is unavailable");
		}

		LOGGER.debug("Verify that driver operations throw");
		assertThrows(FlushFailureException.class, driver::flush,
			"Flush disallowed during outage");
		assertThrows(Exception.class, () -> driver.submitReplacement(bosk.rootReference(), initialRoot(bosk)),
			"Updates disallowed during outage");

		LOGGER.debug("Restore mongo connection");
		mongoService.restoreConnection();

		LOGGER.debug("Flush and check that the state updates");
		waitFor(driver);
		try (var _ = bosk.readContext()) {
			assertEquals(initialState, bosk.rootReference().value(),
				"Updates to database state once it reconnects");
		}

		LOGGER.debug("Make a change to the bosk and verify that it gets through");
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withString("distinctive string")
			.withListing(Listing.of(refs.catalog(), entity123));


		waitFor(driver);
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
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
				// Let's wait several times the timescale so that the test
				// can set a short timescale to make FLUSH fast without risking
				// failures in the WAIT tests.
				//
				// Unfortunately, this makes these tests inevitably slow.
				//
				Thread.sleep(10L * driverSettings.timescaleMS());
				break;
		}
	}

	@InjectedTest
	void databaseDropped_recovers() throws InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop database");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (_) -> initializeDatabase("after drop"));
	}

	@InjectedTest
	void collectionDropped_recovers() throws InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop collection");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (_) -> initializeDatabase("after drop"));
	}

	@InjectedTest
	void documentDeleted_recovers() throws InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Delete document");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.deleteMany(new BsonDocument());
		}, (_) -> initializeDatabase("after deletion"));
	}

	@InjectedTest
	void documentReappears_recovers() throws InterruptedException, IOException {
		MongoCollection<Document> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME);
		AtomicReference<Document> originalDocument = new AtomicReference<>();
		BsonString rootDocumentID = (driverSettings.preferredDatabaseFormat() == MongoDriverSettings.DatabaseFormat.SEQUOIA)?
			SequoiaFormatDriver.DOCUMENT_ID :
			PandoFormatDriver.ROOT_DOCUMENT_ID;
		BsonDocument rootDocumentFilter = new BsonDocument("_id", rootDocumentID);
		testRecovery(() -> {
			LOGGER.debug("Save original document");
			try (var cursor = collection.find(rootDocumentFilter).cursor()) {
				originalDocument.set(cursor.next());
			}
			LOGGER.debug("Delete document");
			collection.deleteMany(rootDocumentFilter);
		}, (b) -> {
			LOGGER.debug("Restore original document");
			// NOTE: This doesn't actually work cleanly with Pando, because restoring the root document by itself
			// doesn't cause all the subparts to appear in the change stream, which means there's not enough
			// info to reassemble the whole state tree. It ends up failing and reinitializing, so it passes the test.
			collection.insertOne(originalDocument.get());
			return b;
		});
	}

	@InjectedTest
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

		Bosk<TestEntity> bosk = new Bosk<>(boskName(getClass().getSimpleName()), TestEntity.class, this::initialRoot, driverFactory, Bosk.simpleRegistrar());

		try (var _ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Delete revision field");
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME)
			.updateOne(
				new BsonDocument(),
				new BsonDocument("$unset", new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonNull())) // Value is ignored
			);

		LOGGER.debug("Ensure flush works");
		waitFor(bosk.driver());
		try (var _ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Repair by setting revision in the far future");
		setRevision(1000L);

		LOGGER.debug("Ensure flush works again");
		waitFor(bosk.driver());
		try (var _ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}
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

	private TestEntity initializeDatabase(String distinctiveString) {
		try {
			AtomicReference<MongoDriver> driverRef = new AtomicReference<>();
			Bosk<TestEntity> prepBosk = new Bosk<>(
				boskName("Prep " + getClass().getSimpleName()),
				TestEntity.class,
				bosk -> initialRoot(bosk).withString(distinctiveString),
				(b, d) -> {
					var mongoDriver = (MongoDriver) driverFactory.build(b, d);
					driverRef.set(mongoDriver);
					return mongoDriver;
				},
				Bosk.simpleRegistrar());
			var driver = driverRef.get();
			waitFor(driver);
			driver.close();

			return initialRoot(prepBosk).withString(distinctiveString);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private void testRecovery(Runnable disruptiveAction, Function<TestEntity, TestEntity> recoveryAction) throws IOException, InterruptedException {
		LOGGER.debug("Setup database to beforeState");
		TestEntity beforeState = initializeDatabase("before disruption");

		Bosk<TestEntity> bosk = new Bosk<>(boskName(getClass().getSimpleName()), TestEntity.class, this::initialRoot, driverFactory, Bosk.simpleRegistrar());

		try (var _ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Run disruptive action");
		disruptiveAction.run();

		LOGGER.debug("Ensure flush throws");
		assertThrows(FlushFailureException.class, () -> bosk.driver().flush());
		try (var _ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Run recovery action");
		TestEntity afterState = recoveryAction.apply(beforeState);

		LOGGER.debug("Ensure flush works");
		waitFor(bosk.driver());
		try (var _ = bosk.readContext()) {
			assertEquals(afterState, bosk.rootReference().value());
		}
	}

	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverRecoveryTest.class);
}
