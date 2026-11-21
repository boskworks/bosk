package works.bosk.libtesting;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.LoggerFactory;

/**
 * Binds SLF4J to its logging backend before JUnit starts executing tests.
 * <p>
 * SLF4J 2.x initializes its backend lazily.
 * A thread that touches {@link org.slf4j.MDC MDC} while another thread is still
 * initializing the backend is served by the substitute {@code BasicMDCAdapter}.
 * The backend's own adapter replaces it when initialization completes,
 * discarding anything the earlier thread put in the MDC.
 * <p>
 * Tests run classes in parallel, so that window can fall in the middle of a
 * test: {@code MDC.put} lands in the substitute adapter, but the logging call
 * that follows reads the backend's adapter and sees nothing.
 * That makes MDC-dependent tests intermittently lose their context,
 * or record an event with an empty MDC map.
 * <p>
 * Initializing the backend here, on the single thread that runs this listener
 * before any test executes, closes the window.
 */
public class Slf4jInitializer implements TestExecutionListener {
	private static volatile boolean initialized;

	@Override
	public void testPlanExecutionStarted(TestPlan testPlan) {
		LoggerFactory.getILoggerFactory();
		initialized = true;
	}

	/**
	 * @return true if this listener has run, meaning SLF4J was initialized
	 * before any test in the current test plan executed
	 */
	static boolean hasRun() {
		return initialized;
	}
}
