package works.bosk.load;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Samples JVM metrics once per second while a measurement phase is running.
 */
final class MetricsSampler {
	private final LongAdder ops;
	private final List<MetricsSample> samples = new CopyOnWriteArrayList<>();
	private long startNanos;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "load-metrics-sampler");
		thread.setDaemon(true);
		return thread;
	});

	MetricsSampler(LongAdder ops) {
		this.ops = ops;
	}

	void start() {
		this.startNanos = System.nanoTime();
		executor.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
	}

	void stop() {
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	List<MetricsSample> samples() {
		return List.copyOf(samples);
	}

	private void sample() {
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
		samples.add(new MetricsSample(
			elapsedMillis,
			ops.sum(),
			JvmMetrics.heapUsed(),
			JvmMetrics.heapMax(),
			JvmMetrics.gcCount(),
			JvmMetrics.gcTimeMillis()
		));
	}
}
