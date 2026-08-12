package works.bosk.drivers.sql;

import lombok.With;

/**
 * Test probes: the test-only facilities that {@link SqlDriverImpl} consults at
 * well-defined points, so tests can deterministically coordinate with the
 * driver's internals.
 * <p>
 * All probes are no-ops by default; tests install the probes they need by
 * starting from {@link #noop()} and using the {@code with} methods. The probes
 * are read from {@link SqlDriverImpl#TEST_PROBES} on the thread that constructs
 * the {@code SqlDriverImpl}, and are captured at construction time, so they
 * apply to every thread that later does database work.
 * <p>
 * The probes are:
 * <ul>
 * <li>{@code afterStateRead}: runs after {@code SqlDriverImpl} reads the state
 * row inside a submit operation, before it writes back. Tests can use it to
 * block a thread between its read and its write, keeping the database
 * transaction open, to force a specific interleaving of concurrent
 * submissions.</li>
 * </ul>
 */
@With
record TestProbes(
	Runnable afterStateRead
) {
	static TestProbes noop() {
		return new TestProbes(NOOP);
	}

	private static final Runnable NOOP = () -> {};
}
