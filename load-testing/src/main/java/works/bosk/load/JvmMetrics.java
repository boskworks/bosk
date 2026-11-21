package works.bosk.load;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/**
 * Reads the JVM measurements that the load harness reports.
 */
final class JvmMetrics {

	private JvmMetrics() {
	}

	static long heapUsed() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	static long heapMax() {
		MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		return usage.getMax() < 0 ? usage.getCommitted() : usage.getMax();
	}

	static long gcCount() {
		long total = 0;
		for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
			total += Math.max(0, collector.getCollectionCount());
		}
		return total;
	}

	static long gcTimeMillis() {
		long total = 0;
		for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
			total += Math.max(0, collector.getCollectionTime());
		}
		return total;
	}
}
