package works.bosk;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IntSetMemoryTest {
	private static final int DENSE_VALUES = 10_000;

	private static volatile IntSet lastCreated;

	@Test
	void sparseValueMagnitude_doesNotAffectAllocation() {
		ThreadMXBean bean = allocationCounter();
		warmUp(bean);

		long small = measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(1);
			return set;
		});
		long large = measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(1 << 30);
			return set;
		});

		assertTrue(large <= small * 2,
			"A far value must cost about the same as a near one: " + small + " vs " + large);
	}

	@Test
	void denseValues_useAboutOneBitEach() {
		ThreadMXBean bean = allocationCounter();
		warmUp(bean);

		long allocated = measure(bean, () -> {
			IntSet set = new IntSet();
			for (int i = 0; i < DENSE_VALUES; i++) {
				set.add(i);
			}
			return set;
		});

		assertTrue(allocated < DENSE_VALUES,
			"Dense values must take far less than four bytes each: " + allocated);
	}

	@Test
	void farValue_doesNotAllocateABitvector() {
		ThreadMXBean bean = allocationCounter();
		warmUp(bean);

		long allocated = measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(Integer.MAX_VALUE);
			return set;
		});

		assertTrue(allocated < 1_024,
			"A single far value must not allocate a wide bitvector: " + allocated);
	}

	private static void warmUp(ThreadMXBean bean) {
		measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(1);
			set.add(1 << 20);
			return set;
		});
	}

	private static long measure(ThreadMXBean bean, Supplier<IntSet> creation) {
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = bean.getCurrentThreadAllocatedBytes();
			IntSet set = creation.get();
			long after = bean.getCurrentThreadAllocatedBytes();
			lastCreated = set;
			best = Math.min(best, after - before);
		}
		return best;
	}

	private static ThreadMXBean allocationCounter() {
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
}
