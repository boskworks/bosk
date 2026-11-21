package works.bosk.load;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one load run.
 *
 * @param runId               identifies this run in the CSV output
 * @param startedAt           when the run began
 * @param config              the configuration that produced the run
 * @param environment         the machine and JVM that ran it
 * @param measuredNanos       the actual measurement duration
 * @param ops                 operations completed during measurement
 * @param errors              exceptions seen by the workers, readers, or the verifier
 * @param reads               read operations completed during measurement
 * @param submitLatency       update submission latency
 * @param flushLatency        flush latency; empty when the run never flushed during measurement
 * @param readLatency         read latency; empty when the run had no readers
 * @param verified            whether the final state matched the expected state
 * @param verificationMessage why verification failed, or {@code null}
 * @param verificationChecked the number of targets verification examined
 * @param verificationFailed  the number of targets verification found wrong
 * @param stalled             whether a worker failed to stop before the stall timeout
 * @param firstError          the first exception seen, or {@code null}
 * @param heapUsedBefore      heap in use before the workers started
 * @param heapUsedAfter       heap in use after the workers stopped
 * @param gcCountBefore       total GC count before the run
 * @param gcCountAfter        total GC count after the run
 * @param gcTimeMillisBefore  total GC time before the run
 * @param gcTimeMillisAfter   total GC time after the run
 * @param samples             periodic JVM observations from the measurement phase
 */
public record LoadResult(
	String runId,
	Instant startedAt,
	LoadConfig config,
	EnvironmentInfo environment,
	long measuredNanos,
	long ops,
	long errors,
	long reads,
	LatencySummary submitLatency,
	LatencySummary flushLatency,
	LatencySummary readLatency,
	boolean verified,
	String verificationMessage,
	int verificationChecked,
	int verificationFailed,
	boolean stalled,
	Throwable firstError,
	long heapUsedBefore,
	long heapUsedAfter,
	long gcCountBefore,
	long gcCountAfter,
	long gcTimeMillisBefore,
	long gcTimeMillisAfter,
	List<MetricsSample> samples
) {

	public double throughputOpsPerSecond() {
		if (measuredNanos <= 0) {
			return 0;
		} else {
			return ops / (measuredNanos / 1_000_000_000.0);
		}
	}

	/**
	 * Whether the workers all stopped before the stall timeout.
	 */
	public boolean completed() {
		return !stalled;
	}

	/**
	 * Whether the run is fit to be quoted as a measurement of the system under test: it completed,
	 * saw no errors, and its final state matched the expectation.
	 *
	 * <p>This is deliberately stricter than {@link #verified()}, which can be true for a run that
	 * dropped updates as long as the surviving updates are the ones the workload last recorded.
	 */
	public boolean success() {
		return completed() && errors == 0 && verified;
	}

	public long heapUsedDelta() {
		return heapUsedAfter - heapUsedBefore;
	}

	public long gcCountDelta() {
		return gcCountAfter - gcCountBefore;
	}

	public long gcTimeMillisDelta() {
		return gcTimeMillisAfter - gcTimeMillisBefore;
	}
}
