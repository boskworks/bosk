package works.bosk.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.HdrHistogram.Histogram;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST;

/**
 * Runs one {@link SanityScenario} in a fresh JVM and writes its measurements.
 *
 * <p>One scenario per process keeps JIT state, heap shape and connection pools
 * from carrying over from the previous scenario, which is what makes successive
 * runs comparable.
 */
public final class SanityChild {

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			throw new IllegalArgumentException("Usage: SanityChild <scenario> <output-file> <work-dir>");
		}
		String name = args[0];
		Path output = Path.of(args[1]);
		Path work = Path.of(args[2]);
		SanityScenario scenario = SanityScenario.byName(name)
			.orElseThrow(() -> new IllegalArgumentException("Unknown scenario \"" + name + "\""));

		Measurement measurement = switch (scenario.name()) {
			case "flush-idle" -> flushIdle();
			case "flush-after-burst" -> flushAfterBurst();
			case "init-1000" -> init1000();
			default -> {
				if (!scenario.isLoad()) {
					throw new IllegalArgumentException("No implementation for phase \"" + name + "\"");
				}
				yield runLoad(scenario, work);
			}
		};

		write(output, scenario, measurement);
	}

	private static Measurement runLoad(SanityScenario scenario, Path work) throws Exception {
		List<String> args = new ArrayList<>(scenario.loadArgs());
		args.add("--label=" + scenario.name());
		args.add("--out=" + work.resolve("load-" + scenario.name()));
		LoadConfig config = LoadConfigParser.parse(args.toArray(String[]::new));
		LoadResult result = new LoadRunner().run(config);

		Metrics metrics = new Metrics();
		metrics.add("throughput", result.ops() / seconds(result.measuredNanos()), "ops/s");
		addLatency(metrics, "submit", result.submitLatency());
		addLatency(metrics, "flush", result.flushLatency());
		addLatency(metrics, "read", result.readLatency());
		if (result.reads() > 0) {
			metrics.add("read_throughput", result.reads() / seconds(result.measuredNanos()), "ops/s");
		}
		metrics.add("gc_count", result.gcCountAfter() - result.gcCountBefore(), "count");
		metrics.add("gc_time", result.gcTimeMillisAfter() - result.gcTimeMillisBefore(), "ms");
		if (result.config().hookCount() > 0) {
			metrics.add("hook_invocations", result.hookInvocations(), "count");
			if (result.ops() > 0) {
				metrics.add("hooks_per_update", (double) result.hookInvocations() / result.ops(), "ratio");
			}
		}
		return new Measurement(metrics.list(), result.errors(), result.verified());
	}

	/**
	 * Flush repeatedly with nothing written in between, so the probes find the
	 * stream already quiet.
	 */
	private static Measurement flushIdle() throws Exception {
		try (MongoEnvironment mongo = MongoEnvironment.start()) {
			mongo.dropCollection(DATABASE, COLLECTION);
			AtomicReference<MongoDriver> driver = new AtomicReference<>();
			DisjointFieldUpdates workload = new DisjointFieldUpdates(8, 1, 64);
			Bosk<TestEntity> bosk = newBosk(mongo, workload, driver);
			try {
				bosk.driver().flush();
				int flushes = 50;
				Histogram histogram = new Histogram(SIGNIFICANT_DIGITS);
				long totalNanos = 0;
				for (int i = 0; i < flushes; i++) {
					long start = System.nanoTime();
					bosk.driver().flush();
					long elapsed = System.nanoTime() - start;
					histogram.recordValue(Math.max(1, elapsed));
					totalNanos += elapsed;
				}
				Metrics metrics = new Metrics();
				addLatency(metrics, "flush", LatencySummary.of(histogram));
				metrics.add("flush_throughput", flushes / seconds(totalNanos), "ops/s");
				return new Measurement(metrics.list(), 0, true);
			} finally {
				close(driver);
			}
		}
	}

	/**
	 * Time one flush after a burst of updates, so the backlog probe has work to
	 * find and wait for.
	 */
	private static Measurement flushAfterBurst() throws Exception {
		try (MongoEnvironment mongo = MongoEnvironment.start()) {
			mongo.dropCollection(DATABASE, COLLECTION);
			AtomicReference<MongoDriver> driver = new AtomicReference<>();
			DisjointFieldUpdates workload = new DisjointFieldUpdates(8, 1, 64);
			Bosk<TestEntity> bosk = newBosk(mongo, workload, driver);
			try {
				workload.initialize(bosk);
				bosk.driver().flush();
				int bursts = 20;
				int updatesPerBurst = 100;
				Histogram histogram = new Histogram(SIGNIFICANT_DIGITS);
				for (int burst = 0; burst < bursts; burst++) {
					for (int i = 0; i < updatesPerBurst; i++) {
						workload.submit(bosk.driver(), 0, burst * (long) updatesPerBurst + i);
					}
					long start = System.nanoTime();
					bosk.driver().flush();
					histogram.recordValue(Math.max(1, System.nanoTime() - start));
				}
				Metrics metrics = new Metrics();
				addLatency(metrics, "flush", LatencySummary.of(histogram));
				metrics.add("bursts", bursts, "count");
				metrics.add("updates_per_burst", updatesPerBurst, "count");
				return new Measurement(metrics.list(), 0, true);
			} finally {
				close(driver);
			}
		}
	}

	/**
	 * Time the construction of a bosk whose collection holds a thousand entries,
	 * which includes serializing the initial state, writing it, and loading it
	 * back.
	 */
	private static Measurement init1000() throws Exception {
		try (MongoEnvironment mongo = MongoEnvironment.start()) {
			mongo.dropCollection(DATABASE, COLLECTION);
			AtomicReference<MongoDriver> driver = new AtomicReference<>();
			int entries = 1000;
			DisjointFieldUpdates workload = new DisjointFieldUpdates(entries, 1, 64);
			long start = System.nanoTime();
			Bosk<TestEntity> bosk = newBosk(mongo, workload, driver);
			long elapsed = System.nanoTime() - start;
			try {
				Metrics metrics = new Metrics();
				metrics.add("init", elapsed / 1e6, "ms");
				metrics.add("entries", entries, "count");
				return new Measurement(metrics.list(), 0, true);
			} finally {
				close(driver);
			}
		}
	}

	private static Bosk<TestEntity> newBosk(MongoEnvironment mongo, DisjointFieldUpdates workload, AtomicReference<MongoDriver> driver) throws InvalidTypeException {
		return new Bosk<>(
			"sanity-" + UUID.randomUUID(),
			TestEntity.class,
			workload::initialRoot,
			BoskConfig.<TestEntity>builder()
				.driverFactory(mongoDriverFactory(mongo, driver))
				.build());
	}

	private static DriverFactory<TestEntity> mongoDriverFactory(MongoEnvironment mongo, AtomicReference<MongoDriver> driver) {
		MongoDriverSettings settings = MongoDriverSettings.builder()
			.database(DATABASE)
			.collection(COLLECTION)
			.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog", "/sideTable"))
			.initialDatabaseUnavailableMode(FAIL_FAST)
			.timescaleMS(TIMESCALE_MS)
			.build();
		return (boskInfo, downstream) -> {
			MongoDriver newDriver = MongoDriver.<TestEntity>factory(mongo.clientSettings(), settings, new BsonSerializer())
				.build(boskInfo, downstream);
			driver.set(newDriver);
			return newDriver;
		};
	}

	private static void close(AtomicReference<MongoDriver> driver) {
		MongoDriver mongoDriver = driver.get();
		if (mongoDriver != null) {
			mongoDriver.close();
		}
	}

	private static void addLatency(Metrics metrics, String prefix, LatencySummary summary) {
		if (summary.count() > 0) {
			metrics.add(prefix + "_p50", summary.p50Millis(), "ms");
			metrics.add(prefix + "_p95", summary.p95Millis(), "ms");
			metrics.add(prefix + "_p99", summary.p99Millis(), "ms");
			metrics.add(prefix + "_max", summary.maxMillis(), "ms");
		}
	}

	private static double seconds(long nanos) {
		return nanos / 1e9;
	}

	private static void write(Path output, SanityScenario scenario, Measurement measurement) throws IOException {
		Files.createDirectories(output.toAbsolutePath().getParent());
		StringBuilder text = new StringBuilder();
		text.append("scenario=").append(scenario.name()).append('\n');
		text.append("group=").append(scenario.group()).append('\n');
		text.append("errors=").append(measurement.errors()).append('\n');
		text.append("verified=").append(measurement.verified()).append('\n');
		for (Metric metric : measurement.metrics()) {
			text.append("metric\t")
				.append(metric.name()).append('\t')
				.append(String.format(Locale.ROOT, "%.6f", metric.value())).append('\t')
				.append(metric.unit()).append('\n');
		}
		text.append("metric\terrors\t").append(measurement.errors()).append("\tcount\n");
		text.append("metric\tverified\t").append(measurement.verified() ? 1 : 0).append("\tbool\n");
		Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
	}

	record Metric(String name, double value, String unit) {
	}

	record Measurement(List<Metric> metrics, long errors, boolean verified) {
	}

	private static final class Metrics {
		private final List<Metric> metrics = new ArrayList<>();

		void add(String name, double value, String unit) {
			metrics.add(new Metric(name, value, unit));
		}

		List<Metric> list() {
			return List.copyOf(metrics);
		}
	}

	private static final int SIGNIFICANT_DIGITS = 3;
	private static final int TIMESCALE_MS = 20_000;
	private static final String DATABASE = "bosk-sanity";
	private static final String COLLECTION = "state";
}
