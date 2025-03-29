package works.bosk;

import works.bosk.exceptions.InvalidTypeException;

/**
 * Provides access to a subset of bosk functionality that is available while the
 * {@link BoskDriver} is being constructed, before the {@link Bosk} itself is fully initialized.
 */
public interface BoskInfo<R extends StateTreeNode> {
	String name();
	Identifier instanceID();
	RootReference<R> rootReference();
	BoskDiagnosticContext diagnosticContext();
	void registerHooks(Object receiver) throws InvalidTypeException;

	/**
	 * @throws IllegalStateException if called before the {@link Bosk} constructor returns
	 */
	Bosk<R> bosk();

}
