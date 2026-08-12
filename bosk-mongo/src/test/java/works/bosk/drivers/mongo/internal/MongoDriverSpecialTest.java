package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.With;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.Document;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskDriver;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.drivers.BufferingDriver;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.BlockingGate;
import works.bosk.logback.BoskLogFilter;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.drivers.state.UpgradeableEntity;
import works.bosk.testing.junit.Slow;
import works.bosk.util.Classes;

import static ch.qos.logback.classic.Level.ERROR;
import static java.lang.Long.max;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests {@link MongoDriver}-specific functionality not covered by {@link MongoDriverConformanceTest}.
 */
@ParameterizedClass
@MethodSource("parameterSets")
@ReplayLogsOnFailure
class MongoDriverSpecialTest extends AbstractMongoDriverTest {
	/**
	 * We deliberately don't reference {@link MainDriver#MANIFEST_ID} here
	 * because if we change the manifest ID then that's a breaking change,
	 * and we want this test to fail.
	 */
	public static final String MANIFEST_ID = "!Manifest";

	private static final String DISCONNECT_PROBE_ID = "disconnectProbe";

	ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream)));
	}

	@AfterEach
	void resetErrorRecording() {
		MainDriver.TEST_PROBES.remove();
	}

	public MongoDriverSpecialTest(ParameterSet parameters) {
		super(parameters.driverSettingsBuilder());
	}

	static List<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				MongoDriverSettings.DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable")
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s
			.timescaleMS(SHORT_TIMESCALE) // Note that some tests can take as long as 25x this
		)).toList();
	}

	@Test
	void quiescent_noErrors() throws InterruptedException, IOException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("quiescent"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		Thread.sleep(12*SHORT_TIMESCALE);

		errorRecorder.assertAllClear("after quiescent period");

		bosk.driver().flush();

		errorRecorder.assertAllClear("after flush");
	}

	/**
	 * TODO: Doesn't {@link works.bosk.testing.drivers.SharedDriverConformanceTest} handle this now?
	 * Should probably just delete this one.
	 */
	@Test
	void warmStart_stateMatches() throws InvalidTypeException, InterruptedException, IOException {
		Bosk<TestEntity> setupBosk = new Bosk<>(
			boskName("Setup"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Refs refs = setupBosk.buildReferences(Refs.class);

		// Make a change to the bosk so it's not just the initial root
		setupBosk.driver().submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		setupBosk.driver().flush();
		TestEntity expected = initialRoot(setupBosk)
			.withListing(Listing.of(refs.catalog(), entity123));

		Bosk<TestEntity> latecomerBosk = new Bosk<>(
			boskName("Latecomer"),
			TestEntity.class,
			_ -> { throw new AssertionError("Default root function should not be called"); },
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		try (var _ = latecomerBosk.readSession()) {
			TestEntity actual = latecomerBosk.rootReference().value();
			assertEquals(expected, actual);
		}
	}

	@Test
	void flush_localStateUpdated() throws InvalidTypeException, InterruptedException, IOException {
		// Set up MongoDriver writing to a modified BufferingDriver that lets us
		// have tight control over all the comings and goings from MongoDriver.
		BlockingQueue<Reference<?>> replacementsSeen = new LinkedBlockingDeque<>();
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory((b, d) -> driverFactory.build(b, new BufferingDriver(d, b.context()) {
				@Override
				public <T> void submitReplacement(Reference<T> target, T newValue) {
					super.submitReplacement(target, newValue);
					replacementsSeen.add(target);
				}
			})).build());

		CatalogReference<TestEntity> catalogRef = bosk.rootReference().thenCatalog(TestEntity.class,
			TestEntity.Fields.catalog);
		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class,
			TestEntity.Fields.listing);

		// Make a change
		Reference<ListingEntry> ref = listingRef.then(entity123);
		bosk.driver().submitReplacement(ref, LISTING_ENTRY);

		// Give the driver a bit of time to make a mistake, if it's going to,
		// but not so long that we cause a timeout that wouldn't otherwise happen
		long budgetMillis = (1+driverSettings.timescaleMS()) / 2;
		while (budgetMillis > 0) {
			long startTime = currentTimeMillis();
			Reference<?> updatedRef = replacementsSeen.poll(budgetMillis, MILLISECONDS);
			if (ref.equals(updatedRef)) {
				// We've seen the expected update. This is pretty likely to be a good time
				// to proceed with the test.
				break;
			} else {
				long elapsedTime = currentTimeMillis() - startTime;
				budgetMillis -= max(elapsedTime, 1); // Always make progress despite the vagaries of the system clock
			}
		}

		try (var _ = bosk.readSession()) {
			TestEntity expected = initialRoot(bosk);
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual, "MongoDriver should not have called downstream.flush() yet");
		}

		bosk.driver().flush();

		try (var _ = bosk.readSession()) {
			TestEntity expected = initialRoot(bosk).withListing(Listing.of(catalogRef, entity123));
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual, "MongoDriver.flush() should reliably update the bosk");
		}

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void listing_stateMatches() throws InvalidTypeException, InterruptedException, IOException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		BoskDriver driver = bosk.driver();
		CatalogReference<TestEntity> catalogRef = bosk.rootReference().thenCatalog(TestEntity.class,
			TestEntity.Fields.catalog);
		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class,
			TestEntity.Fields.listing);

		// Clear the listing
		driver.submitReplacement(listingRef, Listing.empty(catalogRef));

		// Add to the listing
		driver.submitReplacement(listingRef.then(entity124), LISTING_ENTRY);
		driver.submitReplacement(listingRef.then(entity123), LISTING_ENTRY);
		driver.submitReplacement(listingRef.then(entity124), LISTING_ENTRY);

		// Check the contents
		driver.flush();
		try (var _ = bosk.readSession()) {
			Listing<TestEntity> actual = listingRef.value();
			Listing<TestEntity> expected = Listing.of(catalogRef, entity124, entity123);
			assertEquals(expected, actual);
		}

		// Remove an entry
		driver.submitDeletion(listingRef.then(entity123));

		// Check the contents
		driver.flush();
		try (var _ = bosk.readSession()) {
			Listing<TestEntity> actual = listingRef.value();
			Listing<TestEntity> expected = Listing.of(catalogRef, entity124);
			assertEquals(expected, actual);
		}

		errorRecorder.assertAllClear("after test");
	}

	@Test
	@DisruptsMongoProxy
	void networkOutage_boskRecovers() throws InvalidTypeException, InterruptedException, IOException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Refs refs = bosk.buildReferences(Refs.class);
		BoskDriver driver = bosk.driver();

		LOGGER.debug("Wait till MongoDB is up and running");
		driver.flush();

		LOGGER.debug("Make another bosk that doesn't witness any change stream events before the outage");
		Bosk<TestEntity> latecomerBosk = new Bosk<>(
			boskName("Latecomer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());


		errorRecorder.assertAllClear("before cut connection");

		LOGGER.debug("Cut connection");
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());

		assertThrows(FlushFailureException.class, driver::flush);
		assertThrows(FlushFailureException.class, latecomerBosk.driver()::flush);

		LOGGER.debug("Reestablish connection");
		mongoService.restoreConnection();

		LOGGER.debug("Make a change to the bosk and verify that it gets through");
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withListing(Listing.of(refs.catalog(), entity123));

		driver.flush();
		TestEntity actual;
		try (var _ = bosk.readSession()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);

		latecomerBosk.driver().flush();
		TestEntity latecomerActual;
		try (var _ = latecomerBosk.readSession()) {
			latecomerActual = latecomerBosk.rootReference().value();
		}
		assertEquals(expected, latecomerActual);
	}

	@Test
	@DisruptsMongoProxy
	void hookInterrupted_whenReceiverDisconnects() throws InvalidTypeException, InterruptedException, IOException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Refs refs = bosk.buildReferences(Refs.class);
		BoskDriver driver = bosk.driver();

		CountDownLatch hookStarted = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		CountDownLatch hookInterrupted = new CountDownLatch(1);

		// The hook is registered on a listing entry that doesn't exist yet, so registering
		// it doesn't fire the hook; the submit that creates the entry triggers it.
		bosk.hookRegistrar().registerHook("blocking", refs.listingEntry(entity124), ref -> {
			hookStarted.countDown();
			try {
				gate.await();
			} catch (InterruptedException e) {
				hookInterrupted.countDown();
			}
		});

		LOGGER.debug("Wait till MongoDB is up and running");
		driver.flush();

		LOGGER.debug("Submit an update that triggers the blocking hook");
		driver.submitReplacement(refs.listingEntry(entity124), LISTING_ENTRY);

		boolean started = hookStarted.await(30, SECONDS);
		assertTrue(started, "The hook must start running");

		LOGGER.debug("Cut connection and submit an update that fails, disconnecting the driver");
		mongoService.cutConnection();
		tearDownActions.add(() -> mongoService.restoreConnection());

		// The submit runs on a worker thread because, once it triggers the disconnect,
		// it blocks waiting for the receiver to reconnect.
		AtomicReference<Throwable> submitFailure = new AtomicReference<>();
		Thread submitter = new Thread(() -> {
			try {
				driver.submitReplacement(refs.listingEntry(entity124), LISTING_ENTRY);
			} catch (Throwable t) {
				submitFailure.set(t);
			}
		});
		submitter.start();

		boolean interrupted = hookInterrupted.await(30, SECONDS);
		assertTrue(interrupted, "The running hook must receive the interrupt when the receiver disconnects");

		LOGGER.debug("Reestablish connection");
		mongoService.restoreConnection();

		driver.flush();
		submitter.join(30_000);
		assertFalse(submitter.isAlive(), "The submit that triggered the disconnect must complete after recovery");
		Throwable failure = submitFailure.get();
		if (failure != null) {
			throw new AssertionError("Submit during outage failed unexpectedly", failure);
		}

		TestEntity expected = initialRoot(bosk)
			.withListing(Listing.of(refs.catalog(), entity123, entity124));
		TestEntity actual;
		try (var _ = bosk.readSession()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual, "The bosk must recover and converge to the expected state");
	}

	@Test
	@DisruptsMongoProxy
	void hookRegisteredDuringNetworkOutage_works() throws InvalidTypeException, InterruptedException, IOException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Refs refs = bosk.buildReferences(Refs.class);
		BoskDriver driver = bosk.driver();
		CountDownLatch listingEntry124Exists = new CountDownLatch(1);

		bosk.hookRegistrar().registerHook("notice 124", refs.listingEntry(entity124), ref -> {
			if (ref.exists()) {
				listingEntry124Exists.countDown();
			}
		});

		LOGGER.debug("Wait till MongoDB is up and running");
		driver.flush();

		errorRecorder.assertAllClear("before cut connection");

		LOGGER.debug("Cut connection");
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());

		assertThrows(FlushFailureException.class, driver::flush);

		LOGGER.debug("Register hook");
		bosk.hookRegistrar().registerHook("populateListing", refs.catalog(), ref -> {
			LOGGER.debug("Hook populating listing with all ids from catalog");
			try {
				bosk.driver().submitReplacement(refs.listing(), Listing.of(refs.catalog(), ref.value().ids()));
			} catch (DisconnectedException e) {
				LOGGER.debug("Driver is disconnected. We're expecting this to happen at least once.", e);
			}
		});

		LOGGER.debug("Reestablish connection");
		mongoService.restoreConnection();

		LOGGER.debug("Ensure populateListing hook has been triggered");
		driver.flush();

		LOGGER.debug("Wait for listing entry 124 to exist");
		boolean success = listingEntry124Exists.await(30, SECONDS);
		assertTrue(success, "Entry 124 wait should not time out");

		LOGGER.debug("Check bosk state");
		TestEntity expected = initialRoot(bosk)
			.withListing(Listing.of(refs.catalog(), entity123, entity124));

		TestEntity actual;
		try (var _ = bosk.readSession()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	@Test
	@DisruptsMongoProxy
	void networkOutage_changeStreamDoesntNotice_boskRecovers() throws InvalidTypeException, InterruptedException, IOException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		// Make the ChangeReceiver wait when it sees an error.
		// We want the flush operation to encounter the outage first.
		var lock = new Object(){};
		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(d -> new ForwardingChangeListener(d) {
			@Override
			public void onConnectionFailed(Exception cause) throws DownstreamInitialStateException {
				waitUp();
				super.onConnectionFailed(cause);
			}

			@Override
			public void onDisconnect(Throwable e) {
				waitUp();
				super.onDisconnect(e);
			}

			private void waitUp() {
				try {
					LOGGER.debug("Waiting for lock");
					synchronized (lock) { lock.wait(); }
					LOGGER.debug("Got notified");
				} catch (InterruptedException ex) {
					throw new AssertionError(ex);
				} finally {
					LOGGER.debug("Done waiting for lock");
				}
			}
		}));

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Refs refs = bosk.buildReferences(Refs.class);
		BoskDriver driver = bosk.driver();

		LOGGER.debug("Wait till MongoDB is up and running");
		driver.flush();

		LOGGER.debug("Cut connection");
		mongoService.cutConnection();
		tearDownActions.add(()->mongoService.restoreConnection());

		LOGGER.debug("Attempt doomed flush");
		assertThrows(FlushFailureException.class, driver::flush);

		LOGGER.debug("Reestablish connection");
		mongoService.restoreConnection();

		synchronized (lock) {
			LOGGER.debug("Notifying lock");
			lock.notifyAll();
		}

		LOGGER.debug("Make a change to the bosk and verify that it gets through");
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withListing(Listing.of(refs.catalog(), entity123));

		driver.flush();
		TestEntity actual;
		try (var _ = bosk.readSession()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	@Test
	void initialStateHasNonexistentFields_ignored(TestInfo testInfo) throws InvalidTypeException {
		setLogging(ERROR, StateTreeSerializer.class);

		// Upon creating bosk, the initial value will be saved to MongoDB
		new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithValues,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		// Upon creating prevBosk, the state in the database will be loaded into the local.
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		OldEntity expected = OldEntity.withString(rootID.toString(), prevBosk);

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}
		assertEquals(expected, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void updateHasNonexistentFields_ignored(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, StateTreeSerializer.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		TestEntity initialRoot = initialRootWithEmptyCatalog(bosk);
		bosk.driver().submitReplacement(bosk.rootReference(),
			initialRoot
				.withString("replacementString")
				.withValues(Optional.of(TestValues.blank())));

		prevBosk.driver().flush();

		OldEntity oldEntity = OldEntity.withString("replacementString", prevBosk);

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(oldEntity, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void updateNonexistentField_ignored(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, AbstractFormatDriver.class, StateTreeSerializer.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> {
				throw new AssertionError("prevBosk should use the state from MongoDB");
			},
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().submitReplacement(refs.values(),
			TestValues.blank());

		prevBosk.driver().flush();

		OldEntity expected = OldEntity // unchanged from before
			.withString(rootID.toString(), prevBosk);

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(expected, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void deleteNonexistentField_ignored(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, StateTreeSerializer.class);

		Bosk<TestEntity> newerBosk = new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		Refs refs = newerBosk.buildReferences(Refs.class);
		newerBosk.driver().submitDeletion(refs.values());

		prevBosk.driver().flush();

		OldEntity oldEntity = OldEntity.withString(rootID.toString(), prevBosk); // unchanged

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(oldEntity, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	@Slow
	void databaseMissingField_fallsBackToDefaultState(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, ChangeReceiver.class);

		LOGGER.debug("Set up database with entity that has no string field");
		Bosk<OptionalEntity> setupBosk = new Bosk<>(
			boskName("Setup"),
			OptionalEntity.class,
			b -> OptionalEntity.withString(Optional.empty(), b),
			BoskConfig.<OptionalEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Connect another bosk where the string field is mandatory");
		Bosk<TestEntity> testBosk = new Bosk<>(
			boskName("Test"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		TestEntity expected1 = initialRoot(testBosk); // NOT what was put there by the setup bosk!
		TestEntity actual1;
		try (var _ = testBosk.readSession()) {
			actual1 = testBosk.rootReference().value();
		}

		assertEquals(expected1, actual1, "Disconnected bosk should use the default initial root");

		LOGGER.debug("Repair the bosk by writing the string value");
		setupBosk.driver().submitReplacement(
			setupBosk.rootReference().then(String.class, "string"),
			"stringValue");

		LOGGER.debug("Flush testBosk to get the state from the database");
		testBosk.driver().flush();

		Refs refs = testBosk.buildReferences(Refs.class);
		TestEntity expected2;
		try (var _ = setupBosk.readSession()) {
			// (Note that we don't bother flushing setupBosk because we don't need the latest value;
			// the variant field hasn't changed since it was initialized.)
			expected2 = TestEntity.empty(Identifier.from("optionalEntity"), refs.catalog())
				.withString("stringValue")
				.withVariant(setupBosk.rootReference().value().variant().get());
		}

		TestEntity actual2;
		try (var _ = testBosk.readSession()) {
			actual2 = testBosk.rootReference().value();
		}

		assertEquals(expected2, actual2, "Reconnected bosk should see the state from the database");

		assertEquals(0, errorRecorder.failureCount, "No connection failures");
		assertEquals(1, errorRecorder.disconnections.size(),
			"Expected 1 disconnection: DatabaseLoadException from DISCONNECT fallback");
	}

	@Test
	@Slow
	void revisionFieldWrongType_flushThrowsFlushFailureException() throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("revisionWrongType"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		try (var _ = bosk.readSession()) {
			assertEquals(initialRoot(bosk), bosk.rootReference().value());
		}

		// Corrupt the revision field by giving it the wrong BSON type.
		// The $exists filter targets only the document(s) that have a revision field,
		// which is the root document in both formats.
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME, BsonDocument.class)
			.updateMany(
				new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonDocument("$exists", BsonBoolean.TRUE)),
				new BsonDocument("$set", new BsonDocument(Formatter.DocumentFields.revision.name(), new BsonString("oops")))
			);

		// A malformed revision must surface as a checked FlushFailureException at the driver
		// boundary, never as a raw RuntimeException like BsonInvalidOperationException.
		assertThrows(FlushFailureException.class, () -> bosk.driver().flush());
	}

	@Test
	@DisruptsMongoProxy
	void downstreamInitialStateThrows_wrappedInIllegalArgumentException() {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		// Force the downstream driver to be used for initial state
		mongoService.cutConnection();
		tearDownActions.add(() -> mongoService.restoreConnection());

		IOException thrown = new IOException("downstream initial state failed");
		var e = assertThrows(IllegalArgumentException.class, () -> new Bosk<>(
			boskName("Test"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory((b, d) -> {
				BoskDriver failingDownstream = new ForwardingDriver(d) {
					@Override
					public <R extends StateTreeNode> R initialState(Class<R> rootType) throws IOException {
						throw thrown;
					}
				};
				return driverFactory.build(b, failingDownstream);
			}).build()
		));

		assertEquals("Error computing initial state: downstream initial state failed", e.getMessage());
		assertSame(thrown, e.getCause());
	}

	@Test
	void unrelatedDatabase_ignored() throws InvalidTypeException, IOException, InterruptedException {
		tearDownActions.addFirst(mongoService.client().getDatabase("unrelated")::drop);
		doUnrelatedChangeTest("unrelated", MainDriver.COLLECTION_NAME, plausibleRootDocumentID().getValue());
	}

	@Test
	void unrelatedCollection_ignored() throws InvalidTypeException, IOException, InterruptedException {
		doUnrelatedChangeTest(driverSettings.database(), "unrelated", plausibleRootDocumentID().getValue());
	}

	@Test
	void unrelatedDoc_ignored() throws InvalidTypeException, IOException, InterruptedException {
		doUnrelatedChangeTest(driverSettings.database(), MainDriver.COLLECTION_NAME, "unrelated");
	}

	private void doUnrelatedChangeTest(String databaseName, String collectionName, String docID) throws IOException, InterruptedException, InvalidTypeException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		MongoCollection<Document> counterfeitCollection = mongoService.client()
			.getDatabase(databaseName)
			.getCollection(collectionName);

		// Make a realistic-looking doc to try to fool the driver
		MongoCollection<Document> actualCollection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME);
		Document doc;
		try (MongoCursor<Document> cursor = actualCollection.find().limit(1).cursor()) {
			doc = cursor.next();
		}
		doc.put("_id", docID);
		doc.get("state", Document.class).put("string", "counterfeit");
		counterfeitCollection.insertOne(doc);

		bosk.driver().flush();
		TestEntity expected = initialRoot(bosk);
		try (var _ = bosk.readSession()) {
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual);
		}

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void refurbish_createsField(TestInfo testInfo) throws IOException, InterruptedException {
		// We'll use this as an honest observer of the actual state
		LOGGER.debug("Create Original bosk");
		Bosk<TestEntity> originalBosk = new Bosk<>(
			boskName("Original"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Create Upgradeable bosk");
		Bosk<UpgradeableEntity> upgradeableBosk = new Bosk<>(
			boskName("Upgradeable"),
			UpgradeableEntity.class,
			_ -> { throw new AssertionError("upgradeableBosk should use the state from MongoDB"); },
			BoskConfig.<UpgradeableEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Check state before");
		Optional<TestValues> before;
		try (var _ = originalBosk.readSession()) {
			before = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.empty(), before); // Not there yet

		LOGGER.debug("Call refurbish");
		upgradeableBosk.getDriver(MongoDriver.class).refurbish();
		originalBosk.driver().flush(); // Not the bosk that did refurbish!

		LOGGER.debug("Check state after");
		Optional<TestValues> after;
		try (var _ = originalBosk.readSession()) {
			after = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.of(TestValues.blank()), after); // Now it's there

		errorRecorder.assertAllClear("after test");
	}

	@Test
	@Slow
	void manifestVersionBump_disconnects(TestInfo testInfo) throws IOException, InterruptedException {
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Flush should work");
		bosk.driver().flush();

		errorRecorder.assertAllClear("before manifest version bump");

		LOGGER.debug("Upgrade to an unsupported manifest version");
		MongoCollection<Document> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME);
		collection.updateOne(
			new BsonDocument("_id", new BsonString(MANIFEST_ID)),
			new BsonDocument("$inc", new BsonDocument("version", new BsonInt32(1)))
		);
		// Must also bump the revision number or else flush rightly does nothing
		collection.updateOne(
			rootDocumentsFilter(),
			new BsonDocument("$inc", new BsonDocument("revision", new BsonInt64(1)))
		);

		LOGGER.debug("Flush should throw");
		assertThrows(FlushFailureException.class, ()->bosk.driver().flush());

		LOGGER.debug("Finished");
	}

	@Test
	void refurbish_fixesMetadata(TestInfo testInfo) throws IOException, InterruptedException {
		// Set up the database so it looks basically right
		Bosk<TestEntity> initialBosk = new Bosk<>(
			boskName("Initial"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// (Close this so it doesn't crash when we start mucking with the database)
		initialBosk.getDriver(MongoDriver.class).close();

		// Add a bogus metadata field
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
		String bogusField = "bogusField";
		addFields(collection, bogusField);

		// Make the bosk whose refurbish operation we want to test
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// Get the new bosk reconnected
		bosk.driver().flush();

		// Verify that the fields are indeed there
		BsonDocument filterDoc = rootDocumentsFilter();
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			assertEquals(new BsonString(bogusField), doc.getString(bogusField));
		}

		// Refurbish
		bosk.getDriver(MongoDriver.class).refurbish();

		// Verify the field is now gone
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			assertNull(doc.get(bogusField));
		}

	}

	@Test
	void refurbish_preservesExistingEpoch(TestInfo testInfo) throws IOException, InterruptedException {
		// Make the bosk whose refurbish operation we want to test
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// Get the new bosk connected, which initializes the collection with an epoch
		bosk.driver().flush();

		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
		BsonDocument filterDoc = rootDocumentsFilter();
		BsonString epochBefore;
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			epochBefore = cursor.next().getString(Formatter.DocumentFields.epoch.name());
		}

		// Refurbish
		bosk.getDriver(MongoDriver.class).refurbish();

		// The existing epoch must be preserved exactly
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonString epochAfter = cursor.next().getString(Formatter.DocumentFields.epoch.name());
			assertEquals(epochBefore, epochAfter);
		}

	}

	@Test
	void refurbish_addsMissingEpoch(TestInfo testInfo) throws IOException, InterruptedException {
		// Set up the database so it looks basically right
		Bosk<TestEntity> initialBosk = new Bosk<>(
			boskName("Initial"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());
		initialBosk.getDriver(MongoDriver.class).close();

		// Simulate a legacy collection by removing the epoch field
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
		deleteFields(collection, Formatter.DocumentFields.epoch);

		// Make the bosk whose refurbish operation we want to test
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Main"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		// Get the new bosk connected
		bosk.driver().flush();

		// Refurbish
		bosk.getDriver(MongoDriver.class).refurbish();

		// The missing epoch must now be present, and must be a plausible UUID
		BsonDocument filterDoc = rootDocumentsFilter();
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonString epoch = cursor.next().getString(Formatter.DocumentFields.epoch.name());
			assertNotNull(epoch);
			UUID.fromString(epoch.getValue());
		}
	}

	@Test
	void reconnect_lostPublishSignal_doesNotWait() {
		// Regression test for a lost-signal race condition in waitAndRetry.
		//
		// We use three latches to force this ordering:
		//
		//   Application thread         ChangeReceiver thread
		//   ------------------         ---------------------
		//                              onDisconnect
		//                                super.onDisconnect()  <-- setDisconnectedDriver
		//                                countDown(disconnected)
		//   await(disconnected)
		//   submitReplacement
		//     -> DisconnectedException
		//     -> prePublicationWaitAction
		//          countDown(appAtPreWait)
		//          await(published)     onConnectionSucceeded
		//                                 await(appAtPreWait)
		//                                 publishFormatDriver  <-- signal fires here,
		//                                                          but nobody waiting yet
		//                                 countDown(published)
		//          (await returns)
		//     -> waitAndRetry
		//          acquire lock
		//          double-check: not DisconnectedDriver -> skip await
		//          retry operation

		setLogging(ERROR, ChangeReceiver.class);

		AtomicBoolean initializationDone = new AtomicBoolean(false);
		CountDownLatch disconnected = new CountDownLatch(1);
		CountDownLatch appAtPreWait = new CountDownLatch(1);
		CountDownLatch published = new CountDownLatch(1);

		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream) {
			@Override
			public void onDisconnect(Throwable e) {
				super.onDisconnect(e); // calls setDisconnectedDriver
				if (initializationDone.get()) {
					LOGGER.debug("onDisconnect complete; counting down disconnected");
					disconnected.countDown();
				}
			}

			@Override
			public void onConnectionSucceeded() throws UnrecognizedFormatException, FailedMongoClientSessionException, InterruptedException, IOException, TimeoutException, InvalidCollectionContentsException, InitialStateException {
				if (initializationDone.get()) {
					LOGGER.debug("onConnectionSucceeded waiting for appAtPreWait");
					appAtPreWait.await();
					LOGGER.debug("onConnectionSucceeded proceeding");
					super.onConnectionSucceeded();
					LOGGER.debug("onConnectionSucceeded complete; counting down published");
					published.countDown();
				} else {
					LOGGER.debug("onConnectionSucceeded during initialization; passing through");
					super.onConnectionSucceeded();
				}
			}
		})
			.withPrePublicationWaitAction(() -> {
				LOGGER.debug("pre-wait action: counting down appAtPreWait");
				appAtPreWait.countDown();
				try {
					LOGGER.debug("pre-wait action: waiting for published");
					published.await();
					LOGGER.debug("pre-wait action: published; proceeding to waitAndRetry");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));

		try {
			LOGGER.debug("Create bosk");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("lostSignal"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			initializationDone.set(true);

			LOGGER.debug("Cause disconnection by deleting and re-creating the manifest document");
			MongoCollection<BsonDocument> collection = mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
			BsonDocument originalManifest = collection.findOneAndDelete(
				new BsonDocument("_id", new BsonString(MANIFEST_ID)));
			assertNotNull(originalManifest, "Manifest document must exist");
			collection.insertOne(originalManifest);

			LOGGER.debug("Waiting for disconnect to complete before submitting replacement");
			disconnected.await();

			assertTimeoutPreemptively(
				Duration.ofMillis(3 * SHORT_TIMESCALE),
				() -> bosk.driver().submitReplacement(bosk.rootReference(), initialRoot(bosk)),
				"submitReplacement should finish promptly"
			);

			assertEquals(1, errorRecorder.disconnections.size(),
				"Exactly one disconnection to test reconnection");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted", e);
		} finally {
			MainDriver.TEST_PROBES.remove();
		}
	}

	@Test
	void recoveredDriver_survivesStaleDisconnect() throws InterruptedException, InvalidTypeException {
		// Regression test for a race in doRetryableDriverOperation: when an operation fails
		// after the ChangeReceiver has already recovered, the stale failure must not tear down
		// the healthy driver that was just published.
		//
		// We use the writeInterceptor to hold the operation's write in flight, force a
		// disconnect and recovery, and only then fail the write. The disconnect is forced by
		// arming the listener to reject the next change event (a benign write to a scratch
		// document), rather than by corrupting the database. With the buggy code, the failure
		// triggers setDisconnectedDriver, which disconnects the just-recovered driver and forces
		// yet another disconnect/reconnect cycle. With the fix, the failure is recognized as
		// stale, the recovered driver survives, and the operation succeeds by retrying right away.
		//
		//   Application thread            ChangeReceiver thread
		//   ------------------            ---------------------
		//   submitReplacement
		//     -> writeInterceptor
		//          insert scratch doc
		//          await(reconnected)      onEvent (armed listener throws)
		//                                  onDisconnect
		//                                  onConnectionSucceeded
		//                                    publishFormatDriver (recovered)
		//                                    countDown(reconnected)
		//          throw MongoException
		//     -> setDisconnectedDriver
		//          (must not clobber the recovered driver)
		//     -> waitAndRetry
		//          recovered driver is healthy: no wait, retry immediately

		setLogging(ERROR, ChangeReceiver.class, MainDriver.class);

		AtomicBoolean armed = new AtomicBoolean(false);
		AtomicInteger connectCount = new AtomicInteger();
		AtomicInteger reconnects = new AtomicInteger();
		CountDownLatch reconnected = new CountDownLatch(1);

		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream) {
				@Override
				public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
					if (new BsonString(DISCONNECT_PROBE_ID).equals(event.getDocumentKey().get("_id"))) {
						LOGGER.debug("Rejecting event to force a disconnect");
						throw new UnprocessableEventException("Forced disconnect for test", event.getOperationType());
					} else {
						super.onEvent(event);
					}
				}

				@Override
				public void onConnectionSucceeded() throws UnrecognizedFormatException, FailedMongoClientSessionException, InterruptedException, IOException, TimeoutException, InvalidCollectionContentsException, InitialStateException {
					super.onConnectionSucceeded();
					// The first connection is the initial one; only subsequent connections are reconnects.
					// We can't tell them apart using a flag set by the main thread, because this
					// callback runs on the ChangeReceiver thread and races with the flag being set.
					if (connectCount.incrementAndGet() > 1) {
						LOGGER.debug("onConnectionSucceeded after initialization; count = {}", reconnects.incrementAndGet());
						reconnected.countDown();
					}
				}
			})
			.withWriteInterceptor(filter -> {
				if (armed.compareAndSet(true, false)) {
					LOGGER.debug("Forcing a disconnect and recovery while the write is in flight");
					// One benign change event: the listener will reject it by its _id, forcing a disconnect.
					mongoService.client()
						.getDatabase(driverSettings.database())
						.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class)
						.insertOne(new BsonDocument("_id", new BsonString(DISCONNECT_PROBE_ID)));

					try {
						LOGGER.debug("Waiting for the receiver to recover");
						reconnected.await();
						LOGGER.debug("Receiver has recovered; failing the write");
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new AssertionError("Interrupted", e);
					}
					throw new MongoException("Forced write failure after recovery");
				}
			}));

		try {
			LOGGER.debug("Create bosk");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("recoveredDriver"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			Refs refs = bosk.buildReferences(Refs.class);

			// Fail the operation only after the receiver has recovered from the disconnect
			// that the interceptor forces. The stale failure must not cause another reconnect.
			armed.set(true);
			assertTimeoutPreemptively(
				Duration.ofMillis(20 * SHORT_TIMESCALE),
				() -> bosk.driver().submitReplacement(refs.listingEntry(entity124), LISTING_ENTRY),
				"submitReplacement should succeed by retrying after the recovery"
			);

			assertEquals(1, reconnects.get(),
				"The stale disconnect must not have forced another reconnect cycle");
		} finally {
			MainDriver.TEST_PROBES.remove();
		}
	}

	@Test
	void interruptedFlush_doesNotDisconnectHealthyDriver(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		// Regression test for a bug in doRetryableDriverOperation: an InterruptedException
		// thrown by a driver operation (here, a flush interrupted while waiting on a
		// FlushLock) used to be treated as a general failure, disconnecting a perfectly
		// healthy driver and forcing a full reconnect cycle. An interrupt is not a
		// database-health problem, so the driver must stay connected and the operation
		// should be retried.
		//
		// We gate the change event for a submitted update so the flush has to wait for
		// it, interrupt the flush, then release the event and confirm the driver never
		// disconnected.
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);

		// Use a longer timescale so the flush has a generous timeout while it's blocked
		// waiting for the gated event.
		MongoDriverSettings longTimescaleSettings = driverSettings.toBuilder()
			.timescaleMS(10 * SHORT_TIMESCALE)
			.build();
		DriverFactory<TestEntity> longTimescaleFactory = DriverStack.of(
			BoskLogFilter.withController(logController),
			(info, downstream) ->
				MongoDriver.<TestEntity>factory(
					mongoService.clientSettings(testInfo),
					longTimescaleSettings,
					new BsonSerializer()
				).build(info, downstream)
		);

		AtomicBoolean armed = new AtomicBoolean(false);
		AtomicInteger connectCount = new AtomicInteger();
		AtomicInteger reconnects = new AtomicInteger();
		BlockingGate eventGate = new BlockingGate("the change event for the interrupted flush");

		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream) {
				@Override
				public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
					if (armed.compareAndSet(true, false)) {
						LOGGER.debug("Gating the change event");
						eventGate.signal();
						eventGate.awaitRelease(Duration.ofSeconds(30));
					}
					super.onEvent(event);
				}

				@Override
				public void onConnectionSucceeded() throws UnrecognizedFormatException, FailedMongoClientSessionException, InterruptedException, IOException, TimeoutException, InvalidCollectionContentsException, InitialStateException {
					super.onConnectionSucceeded();
					// The first connection is the initial one; only subsequent connections are reconnects.
					// We can't tell them apart using a flag set by the main thread, because this
					// callback runs on the ChangeReceiver thread and races with the flag being set.
					if (connectCount.incrementAndGet() > 1) {
						LOGGER.debug("onConnectionSucceeded after initialization; count = {}", reconnects.incrementAndGet());
					}
				}
			}));

		try {
			LOGGER.debug("Create bosk");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("interruptedFlush"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(longTimescaleFactory).build());
			BoskDriver driver = bosk.driver();
			Refs refs = bosk.buildReferences(Refs.class);
			driver.flush();

			// Gate the next change event so the flush below has to wait for it
			armed.set(true);
			driver.submitReplacement(refs.listingEntry(entity124), LISTING_ENTRY);
			eventGate.awaitSignal(Duration.ofSeconds(30));

			// Start a flush that will block waiting for the gated event
			AtomicReference<Throwable> flushFailure = new AtomicReference<>();
			Thread flusher = new Thread(() -> {
				try {
					driver.flush();
				} catch (Throwable t) {
					flushFailure.set(t);
				}
			});
			flusher.start();

			// Give the flush a moment to block on the flush lock, then interrupt it
			Thread.sleep(2 * SHORT_TIMESCALE);
			flusher.interrupt();

			// Release the gated event so the flush can complete
			eventGate.release();
			flusher.join(60_000);
			assertFalse(flusher.isAlive(), "Flush should complete after the event is released");
			assertNull(flushFailure.get(), "Interrupting a flush should not fail it; got: " + flushFailure.get());

			assertEquals(0, reconnects.get(),
				"Interrupting a driver operation must not cause a disconnect/reconnect cycle");
			errorRecorder.assertAllClear("after interrupting a flush");
		} finally {
			MainDriver.TEST_PROBES.remove();
		}
	}

	@Test
	void flush_duringReconnect_waitsForFreshState(TestInfo testInfo) throws InvalidTypeException, InterruptedException, IOException {
		// Regression test for a race where, on reconnect, MainDriver publishes the new
		// FormatDriver BEFORE it applies the freshly-loaded database state to the in-memory
		// tree. A flush that failed during the disconnect and is retrying can wake up on
		// the publish, find the new FlushLock's revision already satisfied (because
		// loadAllState seeded it with the loaded revision), and return success while the
		// in-memory state still holds the pre-reconnect contents.
		//
		// We force a disconnect, write fresh state to the database while disconnected, and
		// then hold the ChangeReceiver between publishFormatDriver and
		// downstream.submitReplacement by gating the downstream's submitReplacement. The
		// flush starts while disconnected, fails, and waits for the new driver; it must NOT
		// return until the gate is released and the fresh state has actually been applied.
		//
		//   Application thread            ChangeReceiver thread
		//   ------------------            ---------------------
		//   insert probe document ->      onEvent (rejects it, forcing a disconnect)
		//                                 onDisconnect
		//   await(disconnected)
		//   write fresh state to database
		//   flush()
		//     -> DisconnectedException
		//     -> prePublicationWaitAction
		//          countDown(flushAboutToWait)
		//          await(formatDriverChanged)
		//   countDown(proceedWithReconnect)
		//                                 onConnectionSucceeded
		//                                   loadAllState (seeds the flush lock)
		//                                   publishFormatDriver (wakes the flush)
		//     (flush retries, reads the    downstream.submitReplacement
		//      fresh revision, and must     reconnectGate.signal()
		//      block awaiting it)           reconnectGate.awaitRelease
		//   await(reconnectGate)
		//   assert flush is blocked
		//   reconnectGate.release()        (submitReplacement completes)
		//                                   downstream.flush()
		//                                   onHasBeenApplied (releases the flush)
		//   flush() returns; a read session sees the fresh state

		setLogging(ERROR, MainDriver.class, ChangeReceiver.class, AbstractFormatDriver.class);

		// Use a longer timescale so the retrying flush has a generous timeout while it's
		// blocked waiting for the gated state application.
		MongoDriverSettings longTimescaleSettings = driverSettings.toBuilder()
			.timescaleMS(10 * SHORT_TIMESCALE)
			.build();
		DriverFactory<TestEntity> longTimescaleFactory = DriverStack.of(
			BoskLogFilter.withController(logController),
			(b, d) -> {
				var driver = MongoDriver.<TestEntity>factory(
					mongoService.clientSettings(testInfo),
					longTimescaleSettings,
					new BsonSerializer()
				).build(b, d);
				tearDownActions.addFirst(driver::close);
				return driver;
			}
		);

		AtomicBoolean initializationDone = new AtomicBoolean(false);
		AtomicBoolean armed = new AtomicBoolean(false);
		CountDownLatch disconnected = new CountDownLatch(1);
		CountDownLatch flushAboutToWait = new CountDownLatch(1);
		CountDownLatch proceedWithReconnect = new CountDownLatch(1);
		BlockingGate reconnectGate = new BlockingGate("the reconnect's downstream state application");

		MainDriver.TEST_PROBES.set(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream) {
				@Override
				public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
					if (new BsonString(DISCONNECT_PROBE_ID).equals(event.getDocumentKey().get("_id"))) {
						LOGGER.debug("Rejecting event to force a disconnect");
						throw new UnprocessableEventException("Forced disconnect for test", event.getOperationType());
					} else {
						super.onEvent(event);
					}
				}

				@Override
				public void onDisconnect(Throwable e) {
					super.onDisconnect(e);
					if (initializationDone.get()) {
						LOGGER.debug("onDisconnect complete; counting down disconnected");
						disconnected.countDown();
					}
				}

				@Override
				public void onConnectionSucceeded() throws UnrecognizedFormatException, FailedMongoClientSessionException, InterruptedException, IOException, TimeoutException, InvalidCollectionContentsException, InitialStateException {
					if (initializationDone.get()) {
						LOGGER.debug("onConnectionSucceeded waiting for the test to write fresh state");
						proceedWithReconnect.await();
						LOGGER.debug("onConnectionSucceeded proceeding");
					}
					super.onConnectionSucceeded();
				}
			})
			.withPrePublicationWaitAction(() -> {
				LOGGER.debug("pre-wait action: counting down flushAboutToWait");
				flushAboutToWait.countDown();
			}));

		Thread flusher = null;
		try {
			LOGGER.debug("Create bosk");
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("reconnectFlush"),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory((b, d) -> longTimescaleFactory.build(b, new BufferingDriver(d, b.context()) {
					@Override
					public <T> void submitReplacement(Reference<T> target, T newValue) {
						if (armed.get()) {
							reconnectGate.signal();
							reconnectGate.awaitRelease(Duration.ofSeconds(60));
						}
						super.submitReplacement(target, newValue);
					}
				})).build());
			BoskDriver driver = bosk.driver();
			driver.flush();
			initializationDone.set(true);

			LOGGER.debug("Force a disconnect by inserting a document the listener rejects");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME, BsonDocument.class)
				.insertOne(new BsonDocument("_id", new BsonString(DISCONNECT_PROBE_ID)));
			assertTrue(disconnected.await(30, SECONDS),
				"The driver must disconnect after the rejected event");

			// The gate must not intercept the reconnect's replay of the driver's own
			// initialization events, so arm it only once the disconnect is confirmed.
			armed.set(true);

			LOGGER.debug("Write fresh state to the database while the driver is disconnected");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME, BsonDocument.class)
				.updateOne(
					new BsonDocument("path", new BsonString("/")),
					new BsonDocument("$set", new BsonDocument("state.string", new BsonString("fresh after reconnect")))
						.append("$inc", new BsonDocument("revision", new BsonInt64(1)))
				);

			LOGGER.debug("Start a flush that will fail while disconnected and retry after the reconnect");
			AtomicReference<Throwable> flushFailure = new AtomicReference<>();
			flusher = new Thread(() -> {
				try {
					driver.flush();
				} catch (Throwable t) {
					flushFailure.set(t);
				}
			});
			flusher.start();
			assertTrue(flushAboutToWait.await(30, SECONDS),
				"The flush must fail while disconnected and wait for the reconnect");

			LOGGER.debug("Let the driver reconnect");
			proceedWithReconnect.countDown();
			reconnectGate.awaitSignal(Duration.ofSeconds(30));

			LOGGER.debug("The fresh state is loaded but not yet applied; the flush must still be blocked");
			flusher.join(2 * SHORT_TIMESCALE);
			assertTrue(flusher.isAlive(),
				"flush() must not report success while the in-memory state is still stale");

			LOGGER.debug("Release the reconnect's state application so the flush can complete");
			reconnectGate.release();
			flusher.join(60_000);
			assertFalse(flusher.isAlive(), "flush() must complete once the fresh state has been applied");
			assertNull(flushFailure.get(), "flush() must not fail; got: " + flushFailure.get());

			LOGGER.debug("Check that a read session started after flush() returns sees the fresh state");
			String actual;
			try (var _ = bosk.readSession()) {
				actual = bosk.rootReference().value().string();
			}
			assertEquals("fresh after reconnect", actual,
				"A read session started after flush() returns must see the fresh state");
		} finally {
			reconnectGate.release();
			proceedWithReconnect.countDown();
			if (flusher != null) {
				flusher.join(60_000);
				if (flusher.isAlive()) {
					flusher.interrupt();
					flusher.join();
				}
			}
			MainDriver.TEST_PROBES.remove();
		}
	}

	private void deleteFields(MongoCollection<BsonDocument> collection, Formatter.DocumentFields... fields) {
		BsonDocument fieldsToUnset = new BsonDocument();
		for (Formatter.DocumentFields field: fields) {
			fieldsToUnset.append(field.name(), BsonNull.VALUE); // Value is ignored
		}
		BsonDocument filterDoc = rootDocumentsFilter();
		collection.updateOne(
			filterDoc,
			new BsonDocument("$unset", fieldsToUnset));

		// Let's just make sure they're gone
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			for (Formatter.DocumentFields field: fields) {
				assertNull(doc.get(field.name()));
			}
		}

		errorRecorder.assertAllClear("after test");
	}

	private void addFields(MongoCollection<BsonDocument> collection, String... fieldNames) {
		BsonDocument fieldsToSet = new BsonDocument();
		for (String fieldName: fieldNames) {
			fieldsToSet.append(fieldName, new BsonString(fieldName));
		}
		BsonDocument filterDoc = rootDocumentsFilter();
		collection.updateOne(
			filterDoc,
			new BsonDocument("$set", fieldsToSet));

		// Make sure they exist
		try (MongoCursor<BsonDocument> cursor = collection.find(filterDoc).cursor()) {
			BsonDocument doc = cursor.next();
			for (String fieldName: fieldNames) {
				assertEquals(new BsonString(fieldName), doc.getString(fieldName));
			}
		}

		errorRecorder.assertAllClear("after test");
	}

	private @NonNull BsonDocument rootDocumentsFilter() {
		return new BsonDocument("path", new BsonString("/"));
	}

	@NonNull
	private BsonString plausibleRootDocumentID() {
		return (MongoDriverSettings.DatabaseFormat.SEQUOIA == driverSettings.preferredDatabaseFormat())
			? SequoiaFormatDriver.DOCUMENT_ID
			: new BsonString("|"); // Not every PANDO mode uses this, but hey, it's plausible
	}

	/**
	 * Represents an earlier version of the entity before some fields were added.
	 */
	@With
	public record OldEntity(
		Identifier id,
		String string,
		// We need catalog and sideTable because we use them in our PandoConfiguration
		Catalog<OldEntity> catalog,
		SideTable<OldEntity, OldEntity> sideTable
	) implements Entity {
		public static OldEntity withString(String value, Bosk<OldEntity> bosk) throws InvalidTypeException {
			Reference<Catalog<OldEntity>> catalogRef = bosk.rootReference().then(Classes.catalog(OldEntity.class), "catalog");
			return new OldEntity(
				rootID,
				value,
				Catalog.empty(),
				SideTable.empty(catalogRef)
			);
		}
	}

	/**
	 * A version of {@link TestEntity} where all the fields are {@link Optional} so we
	 * have full control over what fields we set.
	 */
	@With
	public record OptionalEntity(
		Identifier id,
		Optional<String> string,
		Optional<Catalog<TestEntity>> catalog,
		Optional<Listing<TestEntity>> listing,
		Optional<SideTable<TestEntity, TestEntity>> sideTable,
		Optional<SideTable<TestEntity, SideTable<TestEntity, TestEntity>>> nestedSideTable,
		Optional<TaggedUnion<TestEntity.Variant>> variant,
		Optional<TestValues> values
	) implements Entity {
		static OptionalEntity withString(Optional<String> string, Bosk<OptionalEntity> bosk) throws InvalidTypeException {
			CatalogReference<TestEntity> domain = bosk.rootReference().thenCatalog(TestEntity.class, "catalog");
			return new OptionalEntity(
				Identifier.from("optionalEntity"),
				string,
				Optional.of(Catalog.empty()),
				Optional.of(Listing.empty(domain)),
				Optional.of(SideTable.empty(domain)),
				Optional.of(SideTable.empty(domain)),
				Optional.of(TaggedUnion.of(new TestEntity.StringCase("stringCase"))),
				Optional.empty());
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverSpecialTest.class);
}
