package works.bosk.testing;

import java.util.concurrent.atomic.AtomicInteger;
import works.bosk.Bosk;

public class BoskTestUtils {
	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	/**
	 * @param framesToSkip number of topmost stack frames to ignore when determining caller information.
	 *                     0 indicates the caller of this method should be used.
	 *                     The intent is to specify the most informative possible frame for those investigating test failures.
	 * @return an informative string suitable to be used as the name of a {@link Bosk}.
	 * Helpful especially in parallel testing, to figure out which bosk emitted a log message.
	 */
	public static String boskName(int framesToSkip) {
		var caller = StackWalker.getInstance().walk(s -> s.skip(1+framesToSkip).findFirst()).get();
		return "bosk" + boskCounter.incrementAndGet() + "(" + caller.getFileName() + ":" + caller.getLineNumber() + ")";
	}

	public static String boskName() {
		return boskName(1);
	}

	public static String boskName(String prefix) {
		return prefix + " " + boskName(1);
	}

	/**
	 * @param framesToSkip number of topmost stack frames to ignore when determining caller information.
	 *                     0 indicates the caller of this method should be used.
	 *                     The intent is to specify the most informative possible frame for those investigating test failures.
	 */
	public static String boskName(String prefix, int framesToSkip) {
		return prefix + " " + boskName(1 + framesToSkip);
	}

}
