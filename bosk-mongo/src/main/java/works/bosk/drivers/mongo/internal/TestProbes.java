package works.bosk.drivers.mongo.internal;

import java.util.function.UnaryOperator;
import lombok.With;
import works.bosk.Bosk;
import works.bosk.drivers.mongo.internal.MainDriver.MongoClientFactory;

/**
 * Test probes: the test-only facilities that {@link MainDriver} consults at
 * well-defined points, so tests can deterministically coordinate with the
 * driver's internals.
 * <p>
 * All probes are no-ops by default; tests install the probes they need by
 * starting from {@link #noop()} and using the {@code with} methods. The probes
 * are read from {@link MainDriver#TEST_PROBES} on the thread that constructs
 * the {@link MainDriver} (and hence the {@link Bosk}), and are captured at
 * construction time, so they apply to every thread that later does database
 * work.
 * <p>
 * The probes are:
 * <ul>
 * <li>{@code clientFactory}: controls creation of {@code MongoClient}s. Among
 * other things, this lets tests reuse one client across many test bosks, since
 * tests create clients at a furious rate and can overwhelm the operating
 * system's management of ephemeral ports.</li>
 * <li>{@code listenerFactory}: interposes on the {@link ChangeListener} to
 * observe, alter, or inject events.</li>
 * <li>{@code prePublicationWaitAction}: performs an action before acquiring
 * the lock to wait for publication of a new format driver, allowing a race to
 * be induced where the driver is published and the waiting thread misses it.</li>
 * <li>{@code beforeRefurbishDelete}: runs before the refurbish transaction
 * deletes the collection contents, allowing a race to be induced between a
 * refurbish and a concurrent write.</li>
 * <li>{@code findInterceptor}: interposes on database reads; see
 * {@link FindInterceptor}.</li>
 * <li>{@code writeInterceptor}: interposes on database writes; see
 * {@link WriteInterceptor}.</li>
 * <li>{@code commitInterceptor}: interposes on transaction commits; see
 * {@link CommitInterceptor}.</li>
 * </ul>
 */
@With
record TestProbes(
	MongoClientFactory clientFactory,
	UnaryOperator<ChangeListener> listenerFactory,
	Runnable prePublicationWaitAction,
	Runnable beforeRefurbishDelete,
	FindInterceptor findInterceptor,
	WriteInterceptor writeInterceptor,
	CommitInterceptor commitInterceptor
) {
	static TestProbes noop() {
		return new TestProbes(MongoClientFactory.ALWAYS_CREATE, null, NOOP, NOOP, FindInterceptor.identity(), WriteInterceptor.identity(), CommitInterceptor.identity());
	}

	private static final Runnable NOOP = () -> {};
}
