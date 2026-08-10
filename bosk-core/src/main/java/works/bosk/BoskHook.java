package works.bosk;

/**
 * Called to indicate the hook's "scope object" may have been modified.
 * <p>
 * Hooks run on their own virtual thread. If the bosk machinery that runs a hook is
 * interrupted (for example, because a driver is shutting down or disconnecting),
 * bosk delivers the interrupt to the running hook so it can stop promptly;
 * implementations should therefore be written to respond to interruption.
 */
public interface BoskHook<T> {
	/**
	 * @param reference points to an object that may have been modified, corresponding
	 * to the scope on which this hook was registered. The referenced object may or
	 * may not exist.
	 * @throws InterruptedException as a convenience for implementations:
	 * bosk catches the exception, tidies up, and proceeds with the next hook.
	 */
	void onChanged(Reference<T> reference) throws InterruptedException;
}
