package works.bosk.drivers.mongo.internal;

/**
 * An update that first attempts a single logical document write in a
 * retryable-write session.
 * <p>
 * The attempt reports its outcome in an {@link Outcome}: either the update is
 * complete, or it needs a transaction to finish.
 * <p>
 * If the attempt cannot complete without a transaction, it returns an
 * {@link Outcome.NeedsTransaction} that the driver runs once, in a transaction,
 * on the same session.
 * <p>
 * The operation must report {@link Outcome.NeedsTransaction} only if the attempt
 * changed nothing. The transaction must re-check its conditions, because another
 * writer may change the database between the attempt and the transaction.
 * <p>
 * The operation must be safe for the client library to retry as a retryable
 * write. In practice this means the attempt must consist of a single database
 * write to a document identified by {@code _id}, with no read-then-write
 * decision that would be invalid on a second attempt.
 */
@FunctionalInterface
non-sealed interface AtomicUpdateOperation extends UpdateOperation {
	Outcome execute(SessionCollection collection);

	/**
	 * What should happen next after an atomic attempt.
	 */
	sealed interface Outcome {
		/**
		 * The attempt is complete, so the update needs no further work.
		 */
		Outcome DONE = new Done();

		/**
		 * The attempt needs the given transaction to finish the update.
		 */
		static Outcome needsTransaction(TransactionalUpdateOperation operation) {
			return new NeedsTransaction(operation);
		}

		record Done() implements Outcome { }

		record NeedsTransaction(TransactionalUpdateOperation operation) implements Outcome { }
	}
}
