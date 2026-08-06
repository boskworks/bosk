package works.bosk.drivers.mongo.internal;

import java.util.function.UnaryOperator;
import works.bosk.Bosk;
import works.bosk.drivers.mongo.internal.MainDriver.MongoClientFactory;

/**
 * Test-only hooks that {@link MainDriver} consults at well-defined points, so
 * tests can deterministically coordinate with the driver's internals.
 * <p>
 * All hooks are no-ops by default; tests install the hooks they need by
 * starting from {@link #noop()} and using the {@code with} methods. The hooks
 * are read from {@link MainDriver#TEST_HOOKS} on the thread that constructs
 * the {@link MainDriver} (and hence the {@link Bosk}), and are captured at
 * construction time, so they apply to every thread that later does database
 * work.
 * <p>
 * The hooks are:
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
 * </ul>
 */
record TestHooks(
	MongoClientFactory clientFactory,
	UnaryOperator<ChangeListener> listenerFactory,
	Runnable prePublicationWaitAction,
	Runnable beforeRefurbishDelete,
	FindInterceptor findInterceptor
) {
	static TestHooks noop() {
		return new TestHooks(MongoClientFactory.ALWAYS_CREATE, null, NOOP, NOOP, FindInterceptor.identity());
	}

	TestHooks withClientFactory(MongoClientFactory value) {
		return new TestHooks(value, listenerFactory, prePublicationWaitAction, beforeRefurbishDelete, findInterceptor);
	}

	TestHooks withListenerFactory(UnaryOperator<ChangeListener> value) {
		return new TestHooks(clientFactory, value, prePublicationWaitAction, beforeRefurbishDelete, findInterceptor);
	}

	TestHooks withPrePublicationWaitAction(Runnable value) {
		return new TestHooks(clientFactory, listenerFactory, value, beforeRefurbishDelete, findInterceptor);
	}

	TestHooks withBeforeRefurbishDelete(Runnable value) {
		return new TestHooks(clientFactory, listenerFactory, prePublicationWaitAction, value, findInterceptor);
	}

	TestHooks withFindInterceptor(FindInterceptor value) {
		return new TestHooks(clientFactory, listenerFactory, prePublicationWaitAction, beforeRefurbishDelete, value);
	}

	private static final Runnable NOOP = () -> {};
}
