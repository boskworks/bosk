package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.BlockingGate;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Exhibits the Pando {@code loadAllState} race: a concurrent commit between the
 * graft (sub-part) document read and the root document read produces a "torn"
 * load whose revision is new enough that the change-stream events that would
 * correct it are skipped, so the downstream never converges to the database
 * state.
 * <p>
 * The test pauses the load's cursor after it has read the graft document, has a
 * writer commit a change while the cursor is paused, then releases the cursor so
 * it reads the root document with the new revision. The fix makes the load run
 * inside a transaction, so it reads the whole state at one consistent snapshot:
 * a write committed during the load is then caught up by the change stream
 * (rather than being skipped as already-seen). After a flush the bosk must
 * reflect the write.
 */
@ReplayLogsOnFailure
public class MongoDriverLoadRaceTest extends AbstractMongoDriverTest {

	public MongoDriverLoadRaceTest() {
		super(MongoDriverSettings.builder()
			.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
			.timescaleMS(LONG_TIMESCALE)
			.database("MongoDriverLoadRaceTest"));
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
			MainDriver.TEST_HOOKS.set(TestHooks.noop()
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
				MainDriver.TEST_HOOKS.remove();
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
			MainDriver.TEST_HOOKS.remove();
		}

		assertNull(constructionError.get(), "Test bosk construction should not throw");

		Bosk<TestEntity> testBosk = testBoskRef.get();
		testBosk.driver().flush();
		try (var _ = testBosk.readSession()) {
			assertEquals(newCatalog, testBosk.rootReference().value().catalog(),
				"After a flush, the bosk must reflect the write that committed during the load");
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
