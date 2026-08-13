package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Exhibits races between a concurrent write and Pando's load or refurbish
 * operations, both of which read the state outside a transaction.
 * <p>
 * {@link #loadConcurrentWithWrite_mustConverge()}: a concurrent commit between
 * the graft (sub-part) document read and the root document read produces a
 * "torn" load whose revision is new enough that the change-stream events that
 * would correct it are skipped, so the downstream never converges to the
 * database state.
 * <p>
 * {@link #refurbishConcurrentWithWrite_mustNotLoseUpdate()}: a concurrent
 * write that commits after refurbish's read but before its re-scatter's
 * deleteMany is inside the transaction's snapshot (so no write-conflict aborts
 * the refurbish) yet absent from the state that gets re-scattered -- so the
 * write is silently lost.
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
