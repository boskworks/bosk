package works.bosk;

/**
 * The means by which {@link BoskHook hooks} are registered with a {@link Bosk}.
 */
public interface HookRegistrar {
	<T> void registerHook(String name, Reference<T> scope, BoskHook<T> hook);
}
