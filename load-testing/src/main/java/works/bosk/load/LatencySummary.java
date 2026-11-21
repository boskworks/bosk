package works.bosk.load;

import java.util.Locale;
import org.HdrHistogram.Histogram;

/**
 * A summary of one latency distribution.
 *
 * <p>All values are in nanoseconds. Percentiles use {@link Histogram#getValueAtPercentile},
 * which returns the highest value in the bucket containing the requested percentile, so the
 * reported value is conservative.
 */
public record LatencySummary(
	long count,
	double meanNanos,
	long p50Nanos,
	long p95Nanos,
	long p99Nanos,
	long p999Nanos,
	long maxNanos
) {

	public static LatencySummary of(Histogram histogram) {
		if (histogram.getTotalCount() == 0) {
			return empty();
		} else {
			return new LatencySummary(
				histogram.getTotalCount(),
				histogram.getMean(),
				histogram.getValueAtPercentile(50.0),
				histogram.getValueAtPercentile(95.0),
				histogram.getValueAtPercentile(99.0),
				histogram.getValueAtPercentile(99.9),
				histogram.getMaxValue()
			);
		}
	}

	public static LatencySummary empty() {
		return new LatencySummary(0, 0, 0, 0, 0, 0, 0);
	}

	public double meanMillis() {
		return meanNanos / 1_000_000.0;
	}

	public double p50Millis() {
		return p50Nanos / 1_000_000.0;
	}

	public double p95Millis() {
		return p95Nanos / 1_000_000.0;
	}

	public double p99Millis() {
		return p99Nanos / 1_000_000.0;
	}

	public double p999Millis() {
		return p999Nanos / 1_000_000.0;
	}

	public double maxMillis() {
		return maxNanos / 1_000_000.0;
	}

	public static String formatNanos(long nanos) {
		if (nanos < 1_000) {
			return nanos + " ns";
		} else if (nanos < 1_000_000) {
			return String.format(Locale.ROOT, "%.3f us", nanos / 1_000.0);
		} else if (nanos < 1_000_000_000) {
			return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
		} else {
			return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
		}
	}

	public String describe() {
		if (count == 0) {
			return "no samples";
		} else {
			return "count=" + count
				+ " mean=" + formatNanos(Math.round(meanNanos))
				+ " p50=" + formatNanos(p50Nanos)
				+ " p95=" + formatNanos(p95Nanos)
				+ " p99=" + formatNanos(p99Nanos)
				+ " p99.9=" + formatNanos(p999Nanos)
				+ " max=" + formatNanos(maxNanos);
		}
	}
}
