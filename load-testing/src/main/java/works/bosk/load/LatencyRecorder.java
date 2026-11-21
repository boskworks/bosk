package works.bosk.load;

import java.time.Duration;
import org.HdrHistogram.Histogram;

/**
 * Records latency samples for one worker.
 *
 * <p>Each instance is confined to one thread. {@link Histogram} is not thread-safe, and using a
 * histogram per worker avoids contention in the measured path.
 */
public final class LatencyRecorder {
	private final Histogram histogram = newHistogram();

	public void record(long nanos) {
		histogram.recordValue(Math.max(1, Math.min(MAX_TRACKABLE_NANOS, nanos)));
	}

	public LatencySummary summary() {
		return LatencySummary.of(histogram);
	}

	public void addTo(Histogram target) {
		target.add(histogram);
	}

	public static Histogram newHistogram() {
		return new Histogram(MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
	}

	private static final long MAX_TRACKABLE_NANOS = Duration.ofMinutes(1).toNanos();
	private static final int SIGNIFICANT_DIGITS = 3;
}
