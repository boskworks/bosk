package works.bosk.load;

import java.io.IOException;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Command-line entry point for the load harness.
 *
 * <p>Run one configuration per process so that JIT state and heap do not carry over between
 * levels of a ramp. For example:
 *
 * <pre>
 * ./gradlew :load-testing:run --args="--threads=1 --warmup=10s --duration=30s --label=warmup"
 * ./gradlew :load-testing:run --args="--threads=P/2 --warmup=10s --duration=30s --label=half"
 * </pre>
 */
public final class Main {

	public static void main(String[] args) {
		if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
			printUsage();
			return;
		}

		LoadConfig config;
		try {
			config = LoadConfigParser.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			printUsage();
			System.exit(2);
			return;
		}

		LoadResult result;
		try {
			result = new LoadRunner().run(config);
		} catch (InvalidTypeException e) {
			System.err.println("The state type is not compatible with the workload: " + e.getMessage());
			System.exit(3);
			return;
		} catch (IOException e) {
			System.err.println("I/O failure while running the load test: " + e.getMessage());
			System.exit(3);
			return;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Interrupted while running the load test");
			System.exit(3);
			return;
		}

		printResult(result);

		try {
			new LoadResultCsvWriter(config.outputDirectory()).write(result);
			System.out.println();
			System.out.println("Results written to " + config.outputDirectory().toAbsolutePath());
		} catch (IOException e) {
			System.err.println("Unable to write results: " + e.getMessage());
			System.exit(4);
			return;
		}

		if (result.stalled()) {
			System.exit(3);
		} else if (result.errors() > 0 || !result.verified()) {
			System.exit(1);
		}
	}

	private static void printResult(LoadResult result) {
		LoadConfig config = result.config();
		EnvironmentInfo environment = result.environment();
		System.out.println("Run: " + result.runId());
		System.out.println("Driver: " + config.driver()
			+ "  Entities: " + config.entityCount()
			+ "  Threads: " + config.threads()
			+ "  Value bytes: " + config.valueBytes());
		System.out.println("Warmup: " + config.warmup().toMillis() + " ms"
			+ "  Duration: " + config.duration().toMillis() + " ms"
			+ "  Flush: " + config.flushMode());
		System.out.println("Environment: processors=" + environment.availableProcessors()
			+ " performance=" + nullToDash(environment.performanceCores())
			+ " efficiency=" + nullToDash(environment.efficiencyCores())
			+ " maxHeap=" + formatMegabytes(environment.maxMemoryBytes())
			+ " os=" + environment.osName() + "/" + environment.osArch()
			+ " java=" + environment.javaVersion());
		System.out.println();
		System.out.printf("Ops: %d in %d ms  Throughput: %,.0f ops/s  Errors: %d%n",
			result.ops(), result.measuredNanos() / 1_000_000, result.throughputOpsPerSecond(), result.errors());
		System.out.println("Submit latency: " + result.submitLatency().describe());
		System.out.println("Flush latency:  " + result.flushLatency().describe());
		if (config.readers() > 0) {
			System.out.println("Read latency:   " + result.readLatency().describe()
				+ "  (" + result.reads() + " reads, " + config.readShape()
				+ ", " + config.sessionLifetime() + " sessions)");
		}
		System.out.println("Heap: " + formatMegabytes(result.heapUsedBefore())
			+ " -> " + formatMegabytes(result.heapUsedAfter())
			+ "  GC: +" + result.gcCountDelta() + " collections, +" + result.gcTimeMillisDelta() + " ms");
		System.out.println("Verified: " + result.verified()
			+ " (" + result.verificationChecked() + " checked, " + result.verificationFailed() + " failed)"
			+ (result.verificationMessage() == null ? "" : " (" + result.verificationMessage() + ")")
			+ "  Stalled: " + result.stalled());
		System.out.println("Success: " + result.success());
		if (result.firstError() != null) {
			System.out.println("First error:");
			result.firstError().printStackTrace(System.out);
		}
	}

	private static void printUsage() {
		System.out.println("""
			Usage: load-testing [options]

			--driver=simple          Driver: simple, mongo-sequoia, mongo-pando, mongo-pando-grafted
			--entities=10000         Number of entities in the root catalog
			--threads=P              Worker threads; also accepts P/2, 2P, 2*P, or an integer
			--warmup=10s             Warmup duration; supports ms, s, m, h
			--duration=30s           Measurement duration
			--flush=everyOp          Flush mode: everyOp, everyN, or end
			--flushEvery=100         Flush interval for everyN
			--valueBytes=16          Length of the string values written by the workload
			--hookLatency=0ms        Sleep time for each hook; zero disables hooks
			--hookScope=root         Hook scope: root or entity
			--hookCount=1            Number of hooks to register
			--hookQueueMaxSize=1000  Hook queue threshold for overload mode
			--readers=0              Reader threads running alongside the writers; zero disables reads
			--readShape=leaf         Read shape: leaf, entity, or catalog
			--sessionLifetime=perOp  Read session lifetime: perOp or perThread
			--seed=1                 Reserved for future randomized workloads
			--stallTimeout=60s       How long to wait for workers to stop
			--label=...              Label included in the output
			--out=build/load-results Output directory for summary.csv and samples.csv

			Run one configuration per process, for example:

			./gradlew :load-testing:run --args="--threads=1 --warmup=10s --duration=30s"
			./gradlew :load-testing:run --args="--threads=P/2 --warmup=10s --duration=30s"
			./gradlew :load-testing:run --args="--threads=P --warmup=10s --duration=30s"
			./gradlew :load-testing:run --args="--driver=mongo-sequoia --threads=6 --warmup=3s --duration=10s"

			The Mongo drivers start a disposable MongoDB replica set with Testcontainers, so Docker
			must be running. Each run gets a fresh container and a fresh collection.
			""");
	}

	private static String formatMegabytes(long bytes) {
		return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	private static String nullToDash(Integer value) {
		return value == null ? "-" : value.toString();
	}

	private Main() {
	}
}
