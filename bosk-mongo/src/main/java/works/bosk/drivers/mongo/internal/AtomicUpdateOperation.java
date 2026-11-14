package works.bosk.drivers.mongo.internal;

/**
 * An update that first attempts a single logical document write in a
 * retryable-write session.
 * <p>
 * The attempt's {@link Outcome} says what it did. An update that matches
 * nothing is ambiguous: it may have had nothing to do, or it may have missed
 * because the database format changed underneath it. The driver needs to know,
 * so that it can ask the format driver to resolve that ambiguity before
 * reporting the update as a no-op.
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
		 * The attempt matched nothing. Either the update had nothing to do, or
		 * the format changed.
		 */
		Outcome NOTHING_CHANGED = new NothingChanged();

		/**
		 * The attempt needs the given transaction to finish the update.
		 */
		static Outcome needsTransaction(TransactionalUpdateOperation operation) {
			return new NeedsTransaction(operation);
		}

		record Done() implements Outcome { }

		record NothingChanged() implements Outcome { }

		record NeedsTransaction(TransactionalUpdateOperation operation) implements Outcome { }
	}
}
