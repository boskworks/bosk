package works.bosk.load;

import java.nio.file.Path;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * Describes one load-test run.
 *
 * <p>The state shape is fixed for this first slice: a root {@code TestEntity} containing
 * {@link #entityCount()} entries, each with a {@code string} field of {@link #valueBytes()} bytes.
 * The workload updates those strings without conflicts, so the expected final state is
 * deterministic.
 *
 * @param label            a human-readable label included in the output
 * @param driver           the driver under test
 * @param entityCount      how many entries the root catalog contains
 * @param threads          how many worker threads submit updates
 * @param warmup           how long to run before measuring
 * @param duration         how long to measure
 * @param flushMode        when to call {@link works.bosk.BoskDriver#flush()}
 * @param flushEvery       the interval used by {@link FlushMode#EVERY_N}
 * @param valueBytes       the length of the string values written by the workload
 * @param hookLatency      how long each hook sleeps per invocation; zero disables hooks
 * @param hookScope        whether each hook is scoped to the root or to every entity's string
 * @param hookCount        how many hooks to register
 * @param hookQueueMaxSize the hook queue threshold at which overload mode begins
 * @param seed             reserved for future randomized workloads
 * @param stallTimeout     how long to wait for workers to stop before declaring a stall
 * @param outputDirectory  where the summary and sample CSVs are written
 */
public record LoadConfig(
	String label,
	DriverKind driver,
	int entityCount,
	int threads,
	Duration warmup,
	Duration duration,
	FlushMode flushMode,
	int flushEvery,
	int valueBytes,
	Duration hookLatency,
	HookScope hookScope,
	int hookCount,
	int hookQueueMaxSize,
	long seed,
	Duration stallTimeout,
	Path outputDirectory
) {

	public LoadConfig {
		requireNonNull(label, "label");
		requireNonNull(driver, "driver");
		requireNonNull(warmup, "warmup");
		requireNonNull(duration, "duration");
		requireNonNull(flushMode, "flushMode");
		requireNonNull(hookLatency, "hookLatency");
		requireNonNull(hookScope, "hookScope");
		requireNonNull(stallTimeout, "stallTimeout");
		requireNonNull(outputDirectory, "outputDirectory");
		if (entityCount <= 0) {
			throw new IllegalArgumentException("entityCount must be positive");
		}
		if (threads <= 0) {
			throw new IllegalArgumentException("threads must be positive");
		}
		if (warmup.isNegative()) {
			throw new IllegalArgumentException("warmup must not be negative");
		}
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException("duration must be positive");
		}
		if (stallTimeout.isZero() || stallTimeout.isNegative()) {
			throw new IllegalArgumentException("stallTimeout must be positive");
		}
		if (flushEvery <= 0) {
			throw new IllegalArgumentException("flushEvery must be positive");
		}
		if (valueBytes <= 0) {
			throw new IllegalArgumentException("valueBytes must be positive");
		}
		if (hookLatency.isNegative()) {
			throw new IllegalArgumentException("hookLatency must not be negative");
		}
		if (hookCount <= 0) {
			throw new IllegalArgumentException("hookCount must be positive");
		}
		if (hookQueueMaxSize <= 0) {
			throw new IllegalArgumentException("hookQueueMaxSize must be positive");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private String label = "";
		private DriverKind driver = DriverKind.SIMPLE;
		private int entityCount = 10_000;
		private int threads = Runtime.getRuntime().availableProcessors();
		private Duration warmup = Duration.ofSeconds(10);
		private Duration duration = Duration.ofSeconds(30);
		private FlushMode flushMode = FlushMode.EVERY_OP;
		private int flushEvery = 100;
		private int valueBytes = 16;
		private Duration hookLatency = Duration.ZERO;
		private HookScope hookScope = HookScope.ROOT;
		private int hookCount = 1;
		private int hookQueueMaxSize = 1000;
		private long seed = 1L;
		private Duration stallTimeout = Duration.ofSeconds(60);
		private Path outputDirectory = Path.of("build", "load-results");

		public Builder label(String label) {
			this.label = label;
			return this;
		}

		public Builder driver(DriverKind driver) {
			this.driver = driver;
			return this;
		}

		public Builder entityCount(int entityCount) {
			this.entityCount = entityCount;
			return this;
		}

		public Builder threads(int threads) {
			this.threads = threads;
			return this;
		}

		public Builder warmup(Duration warmup) {
			this.warmup = warmup;
			return this;
		}

		public Builder duration(Duration duration) {
			this.duration = duration;
			return this;
		}

		public Builder flushMode(FlushMode flushMode) {
			this.flushMode = flushMode;
			return this;
		}

		public Builder flushEvery(int flushEvery) {
			this.flushEvery = flushEvery;
			return this;
		}

		public Builder valueBytes(int valueBytes) {
			this.valueBytes = valueBytes;
			return this;
		}

		public Builder hookLatency(Duration hookLatency) {
			this.hookLatency = hookLatency;
			return this;
		}

		public Builder hookScope(HookScope hookScope) {
			this.hookScope = hookScope;
			return this;
		}

		public Builder hookCount(int hookCount) {
			this.hookCount = hookCount;
			return this;
		}

		public Builder hookQueueMaxSize(int hookQueueMaxSize) {
			this.hookQueueMaxSize = hookQueueMaxSize;
			return this;
		}

		public Builder seed(long seed) {
			this.seed = seed;
			return this;
		}

		public Builder stallTimeout(Duration stallTimeout) {
			this.stallTimeout = stallTimeout;
			return this;
		}

		public Builder outputDirectory(Path outputDirectory) {
			this.outputDirectory = outputDirectory;
			return this;
		}

		public LoadConfig build() {
			return new LoadConfig(
				label,
				driver,
				entityCount,
				threads,
				warmup,
				duration,
				flushMode,
				flushEvery,
				valueBytes,
				hookLatency,
				hookScope,
				hookCount,
				hookQueueMaxSize,
				seed,
				stallTimeout,
				outputDirectory
			);
		}
	}
}
