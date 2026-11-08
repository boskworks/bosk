package works.bosk.drivers.mongo.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
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
 * Measures the write amplification when replacing a whole graft-point entry
 * that contains sub-parts.
 * <p>
 * A whole-entry replacement rewrites every sub-part, so the number of database
 * operations grows with the number of sub-parts, even when only a scalar field
 * of the entry changed. A field update or a replacement of a single sub-part
 * costs one write. This experiment counts writes per update and measures
 * throughput as the number of sub-parts grows, so the cost is visible rather
 * than inferred.
 * <p>
 * It is skipped unless {@code EXPERIMENT_PARTS} is set. Environment variables:
 * <ul>
 * <li>{@code EXPERIMENT_PARTS} (required): number of child catalog entries in
 * the replaced entry, each of which is a sub-part document</li>
 * <li>{@code EXPERIMENT_THREADS}: number of entries replaced concurrently
 * (default 1); each thread replaces its own entry</li>
 * <li>{@code EXPERIMENT_MODE}: {@code replace-entry} (default) replaces the
 * whole entry, {@code replace-child} replaces one of its child entries, and
 * {@code field} replaces a scalar field of the entry</li>
 * <li>{@code EXPERIMENT_WARMUP_MS}, {@code EXPERIMENT_MEASURE_MS},
 * {@code EXPERIMENT_REPS} as in {@link ParallelUpdateExperiment}</li>
 * </ul>
 */
@ReplayLogsOnFailure
@InjectFields
public class SubPartWriteAmplificationExperiment extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"SubPartWriteAmplificationExperiment",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog", "/catalog/-x-/catalog"))
				.timescaleMS(LONG_TIMESCALE)
				.database("SubPartWriteAmplificationExperiment")));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EXPERIMENT_PARTS", matches = ".+")
	void experiment() throws Exception {
		partCount = Integer.parseInt(System.getenv("EXPERIMENT_PARTS"));
		experimentThreads = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_THREADS", "1"));
		mode = System.getenv().getOrDefault("EXPERIMENT_MODE", "replace-entry");
		warmupMs = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_WARMUP_MS", "10000"));
		measureMs = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_MEASURE_MS", "2000"));
		reps = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_REPS", "5"));

		LongAdder writeCount = new LongAdder();
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(_ -> writeCount.increment()));
		try {
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("sub-part-amplification"),
				TestEntity.class,
				this::initialStateWithSubParts,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();

			// Warm up the change stream.
			bosk.driver().submitReplacement(
				refs.catalog().then(entryId(0)).then(String.class, "string"), "warmup");
			bosk.driver().flush();

			AtomicBoolean stop = new AtomicBoolean(false);
			AtomicLong updateCount = new AtomicLong();
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger failures = new AtomicInteger();
			AtomicReference<Throwable> firstFailure = new AtomicReference<>();
			List<Thread> workers = new ArrayList<>();
			for (int t = 0; t < experimentThreads; t++) {
				final Identifier entryID = entryId(t);
				final TestEntity baseEntry = entryWithSubParts(refs, entryID);
				final Reference<TestEntity> entry = refs.catalog().then(entryID);
				final Reference<String> entryField = entry.then(String.class, "string");
				final Reference<TestEntity> child = refs.childCatalog(entryID).then(childId(0));
				final Reference<String> childField = child.then(String.class, "string");
				final int threadIndex = t;
				workers.add(new Thread(() -> {
					try {
						start.await();
						for (long i = 0; !stop.get(); i++) {
							String value = "value-" + threadIndex + "-" + i;
							switch (mode) {
								case "field" -> bosk.driver().submitReplacement(entryField, value);
								case "replace-child" -> bosk.driver().submitReplacement(child, baseEntry.catalog().get(childId(0)).withString(value));
								default -> bosk.driver().submitReplacement(entry, baseEntry.withString(value));
							}
							updateCount.incrementAndGet();
						}
					} catch (Throwable e) {
						failures.incrementAndGet();
						firstFailure.compareAndSet(null, e);
					}
				}, "experiment-worker-" + t));
			}
			workers.forEach(Thread::start);
			start.countDown();

			Thread.sleep(warmupMs);

			double[] ratesPerRep = new double[reps];
			double[] writesPerUpdatePerRep = new double[reps];
			double[] writesPerSecPerRep = new double[reps];
			for (int rep = 0; rep < reps; rep++) {
				long startNanos = System.nanoTime();
				long updatesBefore = updateCount.get();
				long writesBefore = writeCount.sum();
				Thread.sleep(measureMs);
				long endNanos = System.nanoTime();
				long updates = updateCount.get() - updatesBefore;
				long writes = writeCount.sum() - writesBefore;
				double seconds = (endNanos - startNanos) / 1e9;
				assertTrue(updates > 0, "Measurement window must contain updates");
				ratesPerRep[rep] = updates / seconds;
				writesPerUpdatePerRep[rep] = (double) writes / updates;
				writesPerSecPerRep[rep] = writes / seconds;
			}

			stop.set(true);
			for (Thread worker : workers) {
				worker.join(600_000);
				assertFalse(worker.isAlive(), "Update thread should finish");
			}
			bosk.driver().flush();

			assertEquals(0, failures.get(),
				"Updates should not fail: " + firstFailure.get());

			System.out.printf("AMPLIFICATION mode=%s parts=%d threads=%d "
					+ "trimmedMeanUpdatesPerSec=%.1f trimmedMeanWritesPerUpdate=%.1f trimmedMeanWritesPerSec=%.1f "
					+ "rates=[%s] writesPerUpdate=[%s] writesPerSec=[%s] warmupMs=%d measureMs=%d%n",
				mode, partCount, experimentThreads,
				trimmedMean(ratesPerRep), trimmedMean(writesPerUpdatePerRep), trimmedMean(writesPerSecPerRep),
				format(ratesPerRep), format(writesPerUpdatePerRep), format(writesPerSecPerRep),
				warmupMs, measureMs);
		} finally {
			MainDriver.resetProbes();
		}
	}

	private TestEntity initialStateWithSubParts(Bosk<TestEntity> bosk) throws works.bosk.exceptions.InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Catalog<TestEntity> catalog = Catalog.empty();
		for (int t = 0; t < experimentThreads; t++) {
			catalog = catalog.with(entryWithSubParts(refs, entryId(t)));
		}
		return initialRootWithEmptyCatalog(bosk).withCatalog(catalog);
	}

	private TestEntity entryWithSubParts(Refs refs, Identifier entryID) throws works.bosk.exceptions.InvalidTypeException {
		CatalogReference<TestEntity> childCatalog = refs.childCatalog(entryID);
		Catalog<TestEntity> children = Catalog.empty();
		for (int i = 0; i < partCount; i++) {
			Identifier childID = childId(i);
			Reference<Catalog<TestEntity>> grandchildCatalog = childCatalog.then(childID)
				.thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
			children = children.with(TestEntity.empty(childID, grandchildCatalog));
		}
		return TestEntity.empty(entryID, childCatalog).withCatalog(children);
	}

	private static Identifier entryId(int threadIndex) {
		return Identifier.from("e" + threadIndex);
	}

	private static Identifier childId(int childIndex) {
		return Identifier.from("c" + childIndex);
	}

	private static double trimmedMean(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int first = sorted.length >= 5 ? 1 : 0;
		int last = sorted.length >= 5 ? sorted.length - 1 : sorted.length;
		double sum = 0;
		for (int i = first; i < last; i++) {
			sum += sorted[i];
		}
		return sum / (last - first);
	}

	private static String format(double[] values) {
		StringBuilder result = new StringBuilder();
		for (double value : values) {
			if (result.length() > 0) {
				result.append(',');
			}
			result.append(String.format("%.3f", value));
		}
		return result.toString();
	}

	private int partCount;
	private int experimentThreads;
	private String mode;
	private int warmupMs;
	private int measureMs;
	private int reps;
}
