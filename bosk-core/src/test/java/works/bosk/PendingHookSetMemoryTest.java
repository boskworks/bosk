package works.bosk;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compacted backlog keeps one path entry and stores the interested hook
 * registrations in an {@link IntSet}, so its size follows the number of pending
 * notifications rather than the number of hook registrations.
 *
 * <p>
 * Regressing that would be invisible to the tests that check which hooks run,
 * because the cost of a wide per-reference representation is paid in memory
 * alone. This test measures the memory instead.
 */
class PendingHookSetMemoryTest {

	@Test
	void sizeDoesNotDependOnHowManyHooksAreRegistered() {
		ThreadMXBean bean = AllocationMeter.counter();

		long few = allocatedBytesFor(bean, 100);
		long many = allocatedBytesFor(bean, 10_000);

		assertTrue(many < few * 2,
			"Pending work must cost about the same whether the hook indices span 100 or 10,000: "
				+ few + " bytes vs " + many + " bytes");
	}

	/**
	 * Puts the same pending work into a fresh {@link PendingHookSet} whose hook
	 * indices span {@code [0, registeredHooks)}, and reports how much memory that
	 * allocated.
	 *
	 * <p>
	 * Every path is touched by the same {@value #PENDING_HOOKS} hooks each time,
	 * including the most recently registered one. So the only thing that varies
	 * between runs is how wide the hook index space is.
	 */
	private static long allocatedBytesFor(ThreadMXBean bean, int registeredHooks) {
		Path[] paths = new Path[PATH_COUNT];
		for (int p = 0; p < paths.length; p++) {
			paths[p] = Path.of("entities", "id" + p, "value");
		}
		int[] hookIndices = new int[PENDING_HOOKS];
		for (int i = 0; i < hookIndices.length - 1; i++) {
			hookIndices[i] = i;
		}
		hookIndices[hookIndices.length - 1] = registeredHooks - 1;

		// Warm up class loading and the JIT. The measured run builds a fresh map,
		// so its table growth is included in both measurements.
		AllocationMeter.measure(bean, () -> {
			PendingHookSet warmup = new PendingHookSet();
			for (int p = 0; p < WARMUP_PATHS; p++) {
				addEveryHook(warmup, paths[p], hookIndices);
			}
			return warmup;
		});

		return AllocationMeter.measure(bean, () -> {
			PendingHookSet pending = new PendingHookSet();
			for (Path path : paths) {
				addEveryHook(pending, path, hookIndices);
			}
			return pending;
		});
	}

	private static void addEveryHook(PendingHookSet pending, Path path, int[] hookIndices) {
		for (int hookIndex : hookIndices) {
			pending.add(hookIndex, path, true, true);
		}
	}

	private static final int PATH_COUNT = 1_000;
	private static final int WARMUP_PATHS = 200;
	private static final int PENDING_HOOKS = 49;
}
