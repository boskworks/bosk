package works.bosk;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures how many bytes the current thread allocates while creating an
 * object.
 */
final class AllocationMeter {
	private static volatile Object lastCreated;

	private AllocationMeter() {
	}

	/**
	 * @return the thread bean used to read allocation counts
	 */
	static ThreadMXBean counter() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean,
			"This JVM does not report per-thread allocation");
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		if (!bean.isThreadAllocatedMemoryEnabled()) {
			bean.setThreadAllocatedMemoryEnabled(true);
		}
		assumeTrue(bean.isThreadAllocatedMemoryEnabled(),
			"Per-thread allocation measurement is disabled");
		return bean;
	}

	/**
	 * Creates the object a few times and returns the smallest allocation. The
	 * last object stays reachable, so the JIT cannot discard the allocation.
	 */
	static long measure(ThreadMXBean bean, Supplier<?> creation) {
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = bean.getCurrentThreadAllocatedBytes();
			Object created = creation.get();
			long after = bean.getCurrentThreadAllocatedBytes();
			lastCreated = created;
			best = Math.min(best, after - before);
		}
		return best;
	}
}
