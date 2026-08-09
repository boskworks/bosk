package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.drivers.mongo.MongoDriver;
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
 * Exhibits a race in Pando's refurbish: refurbish reads the current state, then
 * re-scatters it in a transaction, but the read happens OUTSIDE that transaction.
 * A concurrent write that commits after the read but before the re-scatter's
 * deleteMany is therefore inside the transaction's snapshot (so no write-conflict
 * aborts the refurbish) yet absent from the state that gets re-scattered -- so the
 * write is silently lost.
 * <p>
 * The test blocks the refurbish just before its deleteMany, commits a concurrent
 * write while it is paused, then releases it. A correct driver must either
 * include the write in the re-scattered state or be aborted by the write-conflict
 * and retried; either way, after the refurbish the write must still be present.
 */
@ReplayLogsOnFailure
public class MongoDriverRefurbishRaceTest extends AbstractMongoDriverTest {

	public MongoDriverRefurbishRaceTest() {
		super(MongoDriverSettings.builder()
			.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
			.timescaleMS(LONG_TIMESCALE)
			.database("MongoDriverRefurbishRaceTest"));
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
}
