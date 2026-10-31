package works.bosk;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compacted backlog groups pending notifications by hook registration, so
 * that its size follows the number of pending notifications rather than the
 * number of hook registrations.
 *
 * <p>
 * Regressing that would be invisible to the tests that check which hooks run,
 * because the cost of a wide per-reference representation is paid in memory
 * alone. This test measures the memory instead.
 */
class PendingHookSetMemoryTest {

	@Test
	void sizeDoesNotDependOnHowManyHooksAreRegistered() {
		com.sun.management.ThreadMXBean bean = allocationCounter();

		long few = allocatedBytesFor(bean, 100);
		long many = allocatedBytesFor(bean, 10_000);

		assertTrue(many < few * 2,
			"Pending work should cost about the same whether the hook indices span 100 or 10,000: "
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
	private static long allocatedBytesFor(com.sun.management.ThreadMXBean bean, int registeredHooks) {
		Path[] paths = new Path[PATH_COUNT];
		for (int p = 0; p < paths.length; p++) {
			paths[p] = Path.of("entities", "id" + p, "value");
		}
		int[] hookIndices = new int[PENDING_HOOKS];
		for (int i = 0; i < hookIndices.length - 1; i++) {
			hookIndices[i] = i;
		}
		hookIndices[hookIndices.length - 1] = registeredHooks - 1;

		PendingHookSet pending = new PendingHookSet();

		// Warm up, so that class loading and the first table growth stay out of
		// the measured region.
		for (int p = 0; p < WARMUP_PATHS; p++) {
			addEveryHook(pending, paths[p], hookIndices);
		}
		pending.clear();

		long before = bean.getCurrentThreadAllocatedBytes();
		for (Path path : paths) {
			addEveryHook(pending, path, hookIndices);
		}
		return bean.getCurrentThreadAllocatedBytes() - before;
	}

	private static void addEveryHook(PendingHookSet pending, Path path, int[] hookIndices) {
		for (int hookIndex : hookIndices) {
			pending.add(hookIndex, path, true, true);
		}
	}

	private static com.sun.management.ThreadMXBean allocationCounter() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean,
			"This JVM does not report per-thread allocation");
		com.sun.management.ThreadMXBean bean =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		if (!bean.isThreadAllocatedMemoryEnabled()) {
			bean.setThreadAllocatedMemoryEnabled(true);
		}
		assumeTrue(bean.isThreadAllocatedMemoryEnabled(),
			"Per-thread allocation measurement is disabled");
		return bean;
	}

	private static final int PATH_COUNT = 1_000;
	private static final int WARMUP_PATHS = 200;
	private static final int PENDING_HOOKS = 49;
}
