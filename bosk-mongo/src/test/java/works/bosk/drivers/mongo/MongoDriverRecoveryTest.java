package works.bosk.drivers.mongo;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
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
import works.bosk.drivers.state.TestEntity;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.ParametersByName;
import works.bosk.junit.Slow;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskTestUtils.boskName;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.drivers.mongo.MainDriver.COLLECTION_NAME;

/**
 * Tests the kinds of recovery actions a human operator might take to try to get a busted service running again.
 */
@Slow
public class MongoDriverRecoveryTest extends AbstractMongoDriverTest {
	FlushOrWait flushOrWait;

	@BeforeEach
	void overrideLogging() {
		// This test deliberately provokes a lot of warnings, so log errors only
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);
	}

	@ParametersByName
	MongoDriverRecoveryTest(FlushOrWait flushOrWait, TestParameters.ParameterSet parameters) {
		super(parameters.driverSettingsBuilder());
		this.flushOrWait = flushOrWait;
	}

	@SuppressWarnings("unused")
	static Stream<TestParameters.ParameterSet> parameters() {
		return TestParameters.driverSettings(
			Stream.of(
				MongoDriverSettings.DatabaseFormat.SEQUOIA,
				PandoFormat.withGraftPoints("/catalog", "/sideTable")
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s
			.recoveryPollingMS(1500) // Note that some tests can take as long as 10x this
			.flushTimeoutMS(2000) // A little more than recoveryPollingMS
		));
	}

	enum FlushOrWait { FLUSH, WAIT }

	@SuppressWarnings("unused")
	static Stream<FlushOrWait> flushOrWait() {
		return Stream.of(FlushOrWait.values());
	}

	@ParametersByName
	@DisruptsMongoProxy
	void initialOutage_recovers() throws InvalidTypeException, InterruptedException, IOException {
		LOGGER.debug("Set up the database contents to be different from initialRoot");
		TestEntity initialState = initializeDatabase("distinctive string");

		LOGGER.debug("Cut mongo connection");
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());

		LOGGER.debug("Create a new bosk that can't connect");
		Bosk<TestEntity> bosk = new Bosk<TestEntity>(getClass().getSimpleName() + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory, Bosk.simpleRegistrar());

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
				Thread.sleep(2 * driverSettings.recoveryPollingMS());
				break;
		}
	}

	@ParametersByName
	void databaseDropped_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop database");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (b) -> initializeDatabase("after drop"));
	}

	@ParametersByName
	void collectionDropped_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop collection");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (b) -> initializeDatabase("after drop"));
	}

	@ParametersByName
	void documentDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Delete document");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.deleteMany(new BsonDocument());
		}, (b) -> initializeDatabase("after deletion"));
	}

	@ParametersByName
	void documentReappears_recovers() throws InvalidTypeException, InterruptedException, IOException {
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

	@ParametersByName
	void revisionDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
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

		Bosk<TestEntity> bosk = new Bosk<TestEntity>(boskName(getClass().getSimpleName()), TestEntity.class, this::initialRoot, driverFactory, Bosk.simpleRegistrar());

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

	private void testRecovery(Runnable disruptiveAction, Function<TestEntity, TestEntity> recoveryAction) throws IOException, InterruptedException, InvalidTypeException {
		LOGGER.debug("Setup database to beforeState");
		TestEntity beforeState = initializeDatabase("before disruption");

		Bosk<TestEntity> bosk = new Bosk<TestEntity>(boskName(getClass().getSimpleName()), TestEntity.class, this::initialRoot, driverFactory, Bosk.simpleRegistrar());

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
