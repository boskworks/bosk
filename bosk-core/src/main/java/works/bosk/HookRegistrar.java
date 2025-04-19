package works.bosk;

public interface HookRegistrar {
	<T> void registerHook(String name, Reference<T> scope, BoskHook<T> hook);
}
