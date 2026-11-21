package works.bosk.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Appends a run to {@code summary.csv} and its periodic samples to {@code samples.csv}.
 *
 * <p>Both files live in the configured output directory. They are append-only so a ramp of
 * several runs produces one analysis-ready file.
 */
public final class LoadResultCsvWriter {
	private final Path outputDirectory;

	public LoadResultCsvWriter(Path outputDirectory) {
		this.outputDirectory = outputDirectory;
	}

	public void write(LoadResult result) throws IOException {
		Files.createDirectories(outputDirectory);
		Path summary = outputDirectory.resolve("summary.csv");
		Path samples = outputDirectory.resolve("samples.csv");
		append(summary, SUMMARY_HEADER, summaryRow(result));
		for (MetricsSample sample : result.samples()) {
			append(samples, SAMPLES_HEADER, sampleRow(result.runId(), sample));
		}
	}

	private static void append(Path file, List<String> header, List<String> row) throws IOException {
		boolean newFile = !Files.exists(file) || Files.size(file) == 0;
		if (newFile) {
			Files.writeString(file, String.join(",", header) + System.lineSeparator(),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		Files.writeString(file, String.join(",", row) + System.lineSeparator(),
			StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static List<String> summaryRow(LoadResult result) {
		LoadConfig config = result.config();
		EnvironmentInfo environment = result.environment();
		return List.of(
			csv(result.runId()),
			csv(result.startedAt().toString()),
			csv(config.label()),
			csv(config.driver().name()),
			Integer.toString(config.entityCount()),
			Integer.toString(config.threads()),
			Integer.toString(config.valueBytes()),
			Long.toString(config.hookLatency().toMillis()),
			csv(config.hookScope().name()),
			Integer.toString(config.hookCount()),
			Integer.toString(config.hookQueueMaxSize()),
			Integer.toString(config.readers()),
			csv(config.readShape().name()),
			csv(config.sessionLifetime().name()),
			Long.toString(config.warmup().toMillis()),
			Long.toString(config.duration().toMillis()),
			csv(config.flushMode().name()),
			Integer.toString(config.flushEvery()),
			Long.toString(result.ops()),
			Long.toString(result.measuredNanos() / 1_000_000),
			millis(result.throughputOpsPerSecond()),
			Long.toString(result.errors()),
			Long.toString(result.reads()),
			Boolean.toString(result.verified()),
			Integer.toString(result.verificationChecked()),
			Integer.toString(result.verificationFailed()),
			csv(nullToEmpty(result.verificationMessage())),
			Boolean.toString(result.stalled()),
			Boolean.toString(result.success()),
			Long.toString(result.submitLatency().count()),
			nanosToMillis(result.submitLatency().meanNanos()),
			nanosToMillis(result.submitLatency().p50Nanos()),
			nanosToMillis(result.submitLatency().p95Nanos()),
			nanosToMillis(result.submitLatency().p99Nanos()),
			nanosToMillis(result.submitLatency().p999Nanos()),
			nanosToMillis(result.submitLatency().maxNanos()),
			Long.toString(result.flushLatency().count()),
			nanosToMillis(result.flushLatency().meanNanos()),
			nanosToMillis(result.flushLatency().p50Nanos()),
			nanosToMillis(result.flushLatency().p95Nanos()),
			nanosToMillis(result.flushLatency().p99Nanos()),
			nanosToMillis(result.flushLatency().p999Nanos()),
			nanosToMillis(result.flushLatency().maxNanos()),
			Long.toString(result.readLatency().count()),
			nanosToMillis(result.readLatency().meanNanos()),
			nanosToMillis(result.readLatency().p50Nanos()),
			nanosToMillis(result.readLatency().p95Nanos()),
			nanosToMillis(result.readLatency().p99Nanos()),
			nanosToMillis(result.readLatency().p999Nanos()),
			nanosToMillis(result.readLatency().maxNanos()),
			bytesToMegabytes(result.heapUsedBefore()),
			bytesToMegabytes(result.heapUsedAfter()),
			Long.toString(result.gcCountDelta()),
			Long.toString(result.gcTimeMillisDelta()),
			Integer.toString(environment.availableProcessors()),
			nullToEmpty(environment.performanceCores()),
			nullToEmpty(environment.efficiencyCores()),
			bytesToMegabytes(environment.maxMemoryBytes()),
			csv(environment.osName()),
			csv(environment.osArch()),
			csv(environment.javaVersion()),
			csv(environment.cpuModel()),
			csv(result.firstError() == null ? "" : result.firstError().toString())
		);
	}

	private static List<String> sampleRow(String runId, MetricsSample sample) {
		return List.of(
			csv(runId),
			Long.toString(sample.elapsedMillis()),
			Long.toString(sample.ops()),
			bytesToMegabytes(sample.heapUsed()),
			bytesToMegabytes(sample.heapMax()),
			Long.toString(sample.gcCount()),
			Long.toString(sample.gcTimeMillis())
		);
	}

	private static String nanosToMillis(double nanos) {
		return String.format(Locale.ROOT, "%.6f", nanos / 1_000_000.0);
	}

	private static String millis(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String bytesToMegabytes(long bytes) {
		return String.format(Locale.ROOT, "%.3f", bytes / (1024.0 * 1024.0));
	}

	private static String nullToEmpty(Object value) {
		return value == null ? "" : value.toString();
	}

	private static String csv(String value) {
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		} else {
			return value;
		}
	}

	private static final List<String> SUMMARY_HEADER = List.of(
		"run_id", "started_at", "label", "driver", "entities", "threads", "value_bytes",
		"hook_latency_ms", "hook_scope", "hook_count", "hook_queue_max_size",
		"readers", "read_shape", "session_lifetime",
		"warmup_ms", "duration_ms", "flush_mode", "flush_every", "ops", "measured_ms",
		"throughput_ops_per_sec", "errors", "reads", "verified", "verification_checked", "verification_failed",
		"verification_message", "stalled", "success",
		"submit_count", "submit_mean_ms", "submit_p50_ms", "submit_p95_ms", "submit_p99_ms",
		"submit_p999_ms", "submit_max_ms", "flush_count", "flush_mean_ms", "flush_p50_ms",
		"flush_p95_ms", "flush_p99_ms", "flush_p999_ms", "flush_max_ms",
		"read_count", "read_mean_ms", "read_p50_ms", "read_p95_ms", "read_p99_ms",
		"read_p999_ms", "read_max_ms", "heap_used_before_mb",
		"heap_used_after_mb", "gc_count_delta", "gc_time_ms_delta", "available_processors",
		"performance_cores", "efficiency_cores", "max_memory_mb", "os", "arch", "java_version",
		"cpu_model", "first_error"
	);

	private static final List<String> SAMPLES_HEADER = List.of(
		"run_id", "elapsed_ms", "ops", "heap_used_mb", "heap_max_mb", "gc_count", "gc_time_ms"
	);
}
