package works.bosk.drivers.mongo.internal;

/**
 * An update that runs inside a multi-document transaction.
 * <p>
 * The operation runs with an active transaction, which it must not commit. It
 * may read and write multiple documents. If it discovers that it has nothing
 * to do, it returns {@link Outcome#ABORT}; otherwise it returns
 * {@link Outcome#COMMIT}.
 * <p>
 * The operation must be safe to replay. The client library may retry the body
 * on a transient transaction error, so it must derive its decisions from the
 * database contents each time rather than from state captured on a previous
 * attempt.
 */
@FunctionalInterface
non-sealed interface TransactionalUpdateOperation extends UpdateOperation {
	Outcome execute(SessionCollection collection);

	/**
	 * What the operation wants the driver to do with its transaction.
	 */
	enum Outcome {
		/**
		 * The operation changed something and its transaction should be committed.
		 */
		COMMIT,

		/**
		 * The operation found that it had nothing to do, so its transaction should
		 * be aborted rather than committing an empty transaction.
		 */
		ABORT
	}
}
