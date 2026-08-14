package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
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
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Exhibits races between a concurrent write and Pando's load or refurbish
 * operations, both of which read the state outside a transaction.
 */
@ReplayLogsOnFailure
@InjectFields
public class WriteRaceTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"WriteRaceTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
				.timescaleMS(LONG_TIMESCALE)
				.database("WriteRaceTest")));
	}

	/**
	 * A concurrent commit while the load is in flight must not be lost: the change
	 * events for that write must eventually be applied to the in-memory state.
	 */
	@Test
	void loadConcurrentWithWrite_mustConverge() throws Exception {
		BlockingGate loadGate = new BlockingGate("the Pando load's graft read");

		// Initialize the database with a single-entry catalog and keep a writer alive
		// to perform the concurrent write. A single entry yields exactly one graft
		// sub-document ("|catalog|123") plus the root document, so a straddling load
		// produces a valid-but-stale torn state rather than a deserialization error.
		Bosk<TestEntity> writerBosk = new Bosk<>(
			boskName("loadRaceWriter"),
			TestEntity.class,
			this::singleEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		writerBosk.driver().flush();
		Refs refs = writerBosk.buildReferences(Refs.class);
		Catalog<TestEntity> newCatalog = Catalog.of(
			TestEntity.empty(entity123, refs.childCatalog(entity123))
				.withString("changed by the writer"));

		// Load the test bosk on a background thread. Its loadAllState reads the
		// graft sub-document first, then (once released) the root document.
		AtomicReference<Bosk<TestEntity>> testBoskRef = new AtomicReference<>();
		AtomicReference<Throwable> constructionError = new AtomicReference<>();
		Thread loadThread = new Thread(() -> {
			MainDriver.TEST_PROBES.set(TestProbes.noop()
				.withFindInterceptor((filter, options, cursor) ->
					isPandoLoadFind(filter) ? new PausingCursor(cursor, 1, loadGate) : cursor));
			try {
				testBoskRef.set(new Bosk<>(
					boskName("loadRaceTest"),
					TestEntity.class,
					this::singleEntryInitialState,
					BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build()));
			} catch (Throwable e) {
				constructionError.set(e);
			} finally {
				MainDriver.TEST_PROBES.remove();
			}
		});
		loadThread.start();

		try {
			// Wait for the load to read the graft sub-document, then write a change.
			loadGate.awaitSignal(Duration.ofSeconds(30));
			writerBosk.driver().submitReplacement(refs.catalog(), newCatalog);

			// Let the load continue; it will read the root document with the new revision.
			loadGate.release();
			loadThread.join(60_000);
			assertFalse(loadThread.isAlive(), "Test bosk construction should finish");
		} finally {
			loadGate.release();
			if (loadThread.isAlive()) {
				loadThread.interrupt();
				loadThread.join();
			}
			MainDriver.TEST_PROBES.remove();
		}

		assertNull(constructionError.get(), "Test bosk construction should not throw");

		Bosk<TestEntity> testBosk = testBoskRef.get();
		testBosk.driver().flush();
		try (var _ = testBosk.readSession()) {
			assertEquals(newCatalog, testBosk.rootReference().value().catalog(),
				"After a flush, the bosk must reflect the write that committed during the load");
		}
	}

	/**
	 * Deterministically exhibits the race behind
	 * {@link #loadConcurrentWithWrite_mustConverge()}: the change receiver can
	 * process events before the constructor has applied the loaded state to the
	 * in-memory tree, in which case the events are ignored as "root does not
	 * exist" while still advancing the flush lock, leaving the state permanently
	 * stale.
	 */
	@Test
	void mustNotProcessEventsBeforeInitialStateApplied() throws Exception {
		BlockingGate loadGate = new BlockingGate("the Pando load's graft read");
		BlockingGate initialStateGate = new BlockingGate("the load thread before the initial state is applied");
		BlockingGate eventGate = new BlockingGate("the final event of the change tree");
		CountDownLatch initialStateReached = new CountDownLatch(1);
		CountDownLatch eventReached = new CountDownLatch(1);
		CountDownLatch eventProcessed = new CountDownLatch(1);

		// The writer bosk initializes the database and later performs the concurrent write.
		Bosk<TestEntity> writerBosk = new Bosk<>(
			boskName("loadRaceWriter"),
			TestEntity.class,
			this::singleEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		writerBosk.driver().flush();
		Refs refs = writerBosk.buildReferences(Refs.class);
		Catalog<TestEntity> newCatalog = Catalog.of(
			TestEntity.empty(entity123, refs.childCatalog(entity123))
				.withString("changed by the writer"));

		// Load the test bosk on a background thread, with probes that pause the
		// load at the graft read, block the load thread after the state is loaded
		// but before it is applied, and gate the final event of the change tree.
		AtomicReference<Bosk<TestEntity>> testBoskRef = new AtomicReference<>();
		AtomicReference<Throwable> constructionError = new AtomicReference<>();
		Thread loadThread = new Thread(() -> {
			MainDriver.TEST_PROBES.set(TestProbes.noop()
				.withFindInterceptor((filter, options, cursor) ->
					isPandoLoadFind(filter) ? new PausingCursor(cursor, 1, loadGate) : cursor)
				.withListenerFactory(downstream -> new GatingChangeListener(downstream, eventGate, eventReached, eventProcessed))
				.withBeforeInitialStateApplied(() -> {
					initialStateReached.countDown();
					initialStateGate.awaitRelease(Duration.ofSeconds(60));
				}));
			try {
				testBoskRef.set(new Bosk<>(
					boskName("loadRaceTest"),
					TestEntity.class,
					this::singleEntryInitialState,
					BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build()));
			} catch (Throwable e) {
				constructionError.set(e);
			} finally {
				MainDriver.TEST_PROBES.remove();
			}
		});
		loadThread.start();

		try {
			// Wait for the load to read the graft sub-document, then write a change.
			loadGate.awaitSignal(Duration.ofSeconds(30));
			writerBosk.driver().submitReplacement(refs.catalog(), newCatalog);

			// Let the load continue; it reads the root document and completes loading.
			loadGate.release();

			// Wait for the load thread to reach the point just before the initial
			// state is applied, so the in-memory tree is guaranteed to be empty.
			assertTrue(initialStateReached.await(30, SECONDS),
				"The load thread should block before the initial state is applied");

			// The change events for the concurrent write are now buffered. If the
			// driver processes them before the initial state is applied (the bug),
			// the final event of the change tree reaches the gating listener now.
			// If events are deferred until the state is applied (the fix), no event
			// reaches it until after the initial state gate is released below.
			boolean gated = eventReached.await(5, SECONDS);
			eventGate.release();
			if (gated) {
				// The change tree is being processed while the state is still
				// pending; make sure it is fully processed before the state is
				// applied, so the tree cannot be applied to the new root.
				assertTrue(eventProcessed.await(30, SECONDS),
					"The change tree should be processed while the initial state is still pending");
			}

			// Release the initial state, and let the test bosk finish constructing.
			initialStateGate.release();
			loadThread.join(60_000);
			assertFalse(loadThread.isAlive(), "Test bosk construction should finish");
		} finally {
			loadGate.release();
			initialStateGate.release();
			eventGate.release();
			if (loadThread.isAlive()) {
				loadThread.interrupt();
				loadThread.join();
			}
			MainDriver.TEST_PROBES.remove();
		}

		assertNull(constructionError.get(), "Test bosk construction should not throw");

		Bosk<TestEntity> testBosk = testBoskRef.get();
		testBosk.driver().flush();
		try (var _ = testBosk.readSession()) {
			assertEquals(newCatalog, testBosk.rootReference().value().catalog(),
				"The write that committed during the load must not be lost");
		}
	}

	/**
	 * A {@link ChangeListener} that blocks the final event of the change tree
	 * (the root document's update that carries the revision field) until the
	 * test releases {@code eventGate}, so the test can force the tree to be
	 * processed at a chosen moment.
	 */
	private static final class GatingChangeListener implements ChangeListener {
		private final ChangeListener downstream;
		private final BlockingGate eventGate;
		private final CountDownLatch eventReached;
		private final CountDownLatch eventProcessed;

		GatingChangeListener(ChangeListener downstream, BlockingGate eventGate, CountDownLatch eventReached, CountDownLatch eventProcessed) {
			this.downstream = downstream;
			this.eventGate = eventGate;
			this.eventReached = eventReached;
			this.eventProcessed = eventProcessed;
		}

		@Override
		public void onConnectionSucceeded() throws
			FailedMongoClientSessionException,
			InitialStateException,
			InterruptedException,
			InvalidCollectionContentsException,
			IOException,
			TimeoutException,
			UnrecognizedFormatException
		{
			downstream.onConnectionSucceeded();
		}

		@Override
		public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
			boolean finalEvent = isFinalTreeEvent(event);
			if (finalEvent && eventReached.getCount() > 0) {
				eventReached.countDown();
				eventGate.awaitRelease(Duration.ofSeconds(60));
			}
			downstream.onEvent(event);
			if (finalEvent) {
				eventProcessed.countDown();
			}
		}

		/**
		 * True for the root document's update that carries the revision field,
		 * which is the last event of the change tree.
		 */
		private static boolean isFinalTreeEvent(ChangeStreamDocument<BsonDocument> event) {
			BsonValue id = event.getDocumentKey().get("_id");
			return id instanceof BsonString s
				&& s.getValue().endsWith("|")
				&& event.getUpdateDescription() != null
				&& event.getUpdateDescription().getUpdatedFields().containsKey(BsonFormatter.DocumentFields.revision.name());
		}

		@Override
		public void onConnectionFailed(Exception cause) throws DownstreamInitialStateException {
			downstream.onConnectionFailed(cause);
		}

		@Override
		public void onDisconnect(Throwable e) {
			downstream.onDisconnect(e);
		}
	}

	/**
	 * A concurrent write that commits after refurbish's read but before its
	 * re-scatter's deleteMany is inside the transaction's snapshot (so no
	 * write-conflict aborts the refurbish) yet absent from the state that gets
	 * re-scattered -- so the write is silently lost.
	 */
	@Test
	void refurbishConcurrentWithWrite_mustNotLoseUpdate() throws Exception {
		BlockingGate refurbishGate = new BlockingGate("the refurbish deleteMany");

		// The writer bosk initializes the database and later performs the concurrent write.
		Bosk<TestEntity> writerBosk = new Bosk<>(
			boskName("refurbishRaceWriter"),
			TestEntity.class,
			this::singleEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		writerBosk.driver().flush();
		Refs refs = writerBosk.buildReferences(Refs.class);
		Catalog<TestEntity> newCatalog = Catalog.of(
			TestEntity.empty(entity123, refs.childCatalog(entity123))
				.withString("changed by the writer"));

		// Run refurbish on a background thread. The beforeRefurbishDelete hook
		// blocks the refurbish just before its deleteMany (the transaction's
		// first write, and so the point where the transaction's snapshot is taken).
		AtomicReference<Bosk<TestEntity>> refurbisherRef = new AtomicReference<>();
		AtomicReference<Throwable> refurbishError = new AtomicReference<>();
		Thread refurbishThread = new Thread(() -> {
			MainDriver.TEST_PROBES.set(TestProbes.noop()
				.withBeforeRefurbishDelete(() -> {
					refurbishGate.signal();
					refurbishGate.awaitRelease(Duration.ofSeconds(60));
				}));
			try {
				Bosk<TestEntity> refurbisher = new Bosk<>(
					boskName("refurbishRaceRefurbisher"),
					TestEntity.class,
					this::singleEntryInitialState,
					BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
				refurbisherRef.set(refurbisher);
				refurbisher.getDriver(MongoDriver.class).refurbish();
			} catch (Throwable e) {
				refurbishError.set(e);
			} finally {
				MainDriver.TEST_PROBES.remove();
			}
		});
		refurbishThread.start();

		try {
			// Wait for refurbish to reach its deleteMany, then commit a concurrent write.
			refurbishGate.awaitSignal(Duration.ofSeconds(30));
			writerBosk.driver().submitReplacement(refs.catalog(), newCatalog);

			refurbishGate.release();
			refurbishThread.join(60_000);
			assertFalse(refurbishThread.isAlive(), "Refurbish should finish");
		} finally {
			refurbishGate.release();
			if (refurbishThread.isAlive()) {
				refurbishThread.interrupt();
				refurbishThread.join();
			}
			MainDriver.TEST_PROBES.remove();
		}

		assertNull(refurbishError.get(), "Refurbish should not throw");

		// A fresh bosk reads whatever is actually in the database after the refurbish.
		Bosk<TestEntity> checkBosk = new Bosk<>(
			boskName("refurbishRaceCheck"),
			TestEntity.class,
			this::singleEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		checkBosk.driver().flush();
		try (var _ = checkBosk.readSession()) {
			assertEquals(newCatalog, checkBosk.rootReference().value().catalog(),
				"A write committed during refurbish must survive it");
		}
	}

	private TestEntity singleEntryInitialState(Bosk<TestEntity> bosk) throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		return initialRootWithEmptyCatalog(bosk)
			.withCatalog(Catalog.of(TestEntity.empty(entity123, refs.childCatalog(entity123))));
	}

	/**
	 * True for the {@code find} query in
	 * {@link PandoFormatDriver#readBsonStateAndMetadata}, which reads all
	 * documents whose {@code _id} starts with {@code "|"}.
	 */
	private static boolean isPandoLoadFind(Bson filter) {
		BsonDocument asDoc = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		BsonValue idValue = asDoc.get("_id");
		return idValue instanceof BsonRegularExpression regex
			&& regex.getPattern().equals("^[|]");
	}
}
