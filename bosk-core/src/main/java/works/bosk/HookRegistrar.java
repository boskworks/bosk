package works.bosk;

/**
 * The means by which {@link BoskHook hooks} are registered with a {@link Bosk}.
 */
public interface HookRegistrar {
	/**
	 * Causes the given {@link BoskHook} to be called when the given scope
	 * object is updated.
	 *
	 * <p>
	 * The <code>scope</code> reference can be parameterized.
	 * Upon any change to any matching node, or any parent or child of a matching node,
	 * the <code>hook</code> will be called with a {@link Bosk.ReadSession} that captures
	 * the state immediately after the update was applied.
	 * The <code>hook</code> will receive an argument that is the <code>scope</code> reference
	 * with all its parameters (if any) bound.
	 *
	 * <p>
	 * For a given update, hooks are called in the order they were registered.
	 * Updates performed by the <code>hook</code> could themselves trigger hooks.
	 * Such "hook cascades" are performed in breadth-first order, and are queued
	 * as necessary to achieve this; hooks are <em>not</em> called recursively.
	 * Hooks may be called on any thread, including one of the threads that
	 * submitted one of the updates, but they will be called in sequence, such
	 * that each <em>happens-before</em> the next.
	 *
	 * <p>
	 * Before returning, runs the hook on the current bosk state.
	 *
	 */
	<T> void registerHook(String name, Reference<T> scope, BoskHook<T> hook);
}
