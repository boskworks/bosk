package works.bosk.drivers.mongo.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A coarse throughput experiment for concurrent updates to distinct graft
 * documents. It is skipped unless {@code EXPERIMENT_THREADS} is set, so it
 * doesn't run as part of the normal test suite.
 * <p>
 * Run it with, for example:
 * <pre>
 * EXPERIMENT_THREADS=8 ./gradlew :bosk-mongo:test \
 *     --tests 'works.bosk.drivers.mongo.internal.ParallelUpdateExperiment' --rerun
 * </pre>
 * It runs for {@code EXPERIMENT_WARMUP_MS} (default 30000 ms) to reach a steady
 * state, then takes {@code EXPERIMENT_REPS} (default 5) measurements of
 * {@code EXPERIMENT_MEASURE_MS} (default 2000 ms) each and reports the median
 * throughput, along with the fastest measurement. The test also reports the number of document writes per update and the
 * distinct documents written; write counts close to one per update, and one
 * distinct document per thread, show that updates are not contending on a
 * shared document.
 * <p>
 * {@code EXPERIMENT_MODE} selects what each update replaces: {@code field}
 * (the default) replaces one field of an existing entry, and {@code replace}
 * replaces a whole entry.
 * <p>
 * This is deliberately a timing-based experiment rather than a correctness
 * test. It exists to compare driver designs, not to catch regressions.
 */
@ReplayLogsOnFailure
@InjectFields
public class ParallelUpdateExperiment extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"ParallelUpdateExperiment",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
				.timescaleMS(LONG_TIMESCALE)
				.database("ParallelUpdateExperiment")));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EXPERIMENT_THREADS", matches = ".+")
	void experiment() throws Exception {
		experimentThreads = Integer.parseInt(System.getenv("EXPERIMENT_THREADS"));
		String mode = System.getenv().getOrDefault("EXPERIMENT_MODE", "field");
		warmupMs = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_WARMUP_MS", "30000"));
		measureMs = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_MEASURE_MS", "2000"));
		reps = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_REPS", "5"));

		AtomicInteger writeCount = new AtomicInteger();
		Set<String> writtenIds = ConcurrentHashMap.newKeySet();
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor((Bson filter) -> {
			writeCount.incrementAndGet();
			BsonDocument doc = filter.toBsonDocument(BsonDocument.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
			if (doc.get("_id") instanceof BsonString s) {
				writtenIds.add(s.getValue());
			}
		}));
		try {
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("experiment"),
				TestEntity.class,
				this::manyEntryInitialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();

			// Warm up the change stream.
			bosk.driver().submitReplacement(
				refs.catalog().then(Identifier.from("e0")).then(String.class, "string"), "warmup");
			bosk.driver().flush();

			writtenIds.clear();
			AtomicBoolean stop = new AtomicBoolean(false);
			AtomicLong updateCount = new AtomicLong();
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger failures = new AtomicInteger();
			AtomicReference<Throwable> firstFailure = new AtomicReference<>();
			List<Thread> workers = new ArrayList<>();
			for (int t = 0; t < experimentThreads; t++) {
				Identifier id = Identifier.from("e" + t);
				Reference<String> field = refs.catalog().then(id).then(String.class, "string");
				Reference<TestEntity> entry = refs.catalog().then(id);
				TestEntity baseValue = TestEntity.empty(id, refs.childCatalog(id));
				int threadIndex = t;
				workers.add(new Thread(() -> {
					try {
						start.await();
						for (long i = 0; !stop.get(); i++) {
							String value = "value-" + threadIndex + "-" + i;
							if (mode.equals("replace")) {
								bosk.driver().submitReplacement(entry, baseValue.withString(value));
							} else {
								bosk.driver().submitReplacement(field, value);
							}
							updateCount.incrementAndGet();
						}
					} catch (Throwable e) {
						failures.incrementAndGet();
						firstFailure.compareAndSet(null, e);
					}
				}));
			}
			workers.forEach(Thread::start);
			start.countDown();

			Thread.sleep(warmupMs);
			writtenIds.clear();
			long[] updatesPerRep = new long[reps];
			double[] secondsPerRep = new double[reps];
			int[] writesPerRep = new int[reps];
			double[] ratesPerRep = new double[reps];
			double[] writeRatiosPerRep = new double[reps];
			for (int rep = 0; rep < reps; rep++) {
				long startNanos = System.nanoTime();
				long updatesBefore = updateCount.get();
				int writesBefore = writeCount.get();
				Thread.sleep(measureMs);
				long endNanos = System.nanoTime();
				updatesPerRep[rep] = updateCount.get() - updatesBefore;
				writesPerRep[rep] = writeCount.get() - writesBefore;
				secondsPerRep[rep] = (endNanos - startNanos) / 1e9;
				assertTrue(updatesPerRep[rep] > 0, "Measurement window must contain updates");
				ratesPerRep[rep] = updatesPerRep[rep] / secondsPerRep[rep];
				writeRatiosPerRep[rep] = (double) writesPerRep[rep] / updatesPerRep[rep];
			}

			stop.set(true);
			for (Thread worker : workers) {
				worker.join(600_000);
				assertFalse(worker.isAlive(), "Update thread should finish");
			}
			bosk.driver().flush();

			assertEquals(0, failures.get(),
				"Updates should not fail: " + firstFailure.get());

			int bestRep = 0;
			for (int rep = 1; rep < reps; rep++) {
				if (ratesPerRep[rep] > ratesPerRep[bestRep]) {
					bestRep = rep;
				}
			}
			System.out.printf("EXPERIMENT mode=%s threads=%d medianUpdatesPerSec=%.1f medianWritesPerUpdate=%.3f fastestUpdatesPerSec=%.1f fastestWritesPerUpdate=%.3f rates=[%s] writeRatios=[%s] distinctDocs=%d warmupMs=%d measureMs=%d%n",
				mode,
				experimentThreads,
				median(ratesPerRep), median(writeRatiosPerRep),
				ratesPerRep[bestRep], writeRatiosPerRep[bestRep],
				format(ratesPerRep), format(writeRatiosPerRep),
				writtenIds.size(), warmupMs, measureMs);
		} finally {
			MainDriver.resetProbes();
		}
	}

	private static double median(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int middle = sorted.length / 2;
		return sorted.length % 2 == 0
			? (sorted[middle - 1] + sorted[middle]) / 2
			: sorted[middle];
	}

	private static String format(double[] values) {
		StringBuilder result = new StringBuilder();
		for (double value : values) {
			if (result.length() > 0) {
				result.append(',');
			}
			result.append(String.format("%.1f", value));
		}
		return result.toString();
	}

	private TestEntity manyEntryInitialState(Bosk<TestEntity> bosk) throws works.bosk.exceptions.InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Catalog<TestEntity> catalog = Catalog.empty();
		for (int i = 0; i < experimentThreads; i++) {
			Identifier id = Identifier.from("e" + i);
			catalog = catalog.with(TestEntity.empty(id, refs.childCatalog(id)));
		}
		return initialRootWithEmptyCatalog(bosk).withCatalog(catalog);
	}

	private int experimentThreads;
	private int warmupMs;
	private int measureMs;
	private int reps;
}
