package works.bosk;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IntSetMemoryTest {
	private static final int DENSE_VALUES = 10_000;

	@Test
	void sparseValueMagnitude_doesNotAffectAllocation() {
		ThreadMXBean bean = AllocationMeter.counter();
		warmUp(bean);

		long small = AllocationMeter.measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(1);
			return set;
		});
		long large = AllocationMeter.measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(1 << 30);
			return set;
		});

		assertTrue(large <= small * 2,
			"A far value must cost about the same as a near one: " + small + " vs " + large);
	}

	@Test
	void denseValues_useAboutOneBitEach() {
		ThreadMXBean bean = AllocationMeter.counter();
		warmUp(bean);

		long allocated = AllocationMeter.measure(bean, () -> {
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
		ThreadMXBean bean = AllocationMeter.counter();
		warmUp(bean);

		long allocated = AllocationMeter.measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(Integer.MAX_VALUE);
			return set;
		});

		assertTrue(allocated < 1_024,
			"A single far value must not allocate a wide bitvector: " + allocated);
	}

	private static void warmUp(ThreadMXBean bean) {
		AllocationMeter.measure(bean, () -> {
			IntSet set = new IntSet();
			set.add(1);
			set.add(1 << 20);
			return set;
		});
	}
}
