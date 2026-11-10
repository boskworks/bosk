package works.bosk.drivers.mongo.internal;

/**
 * A format-specific description of one Bosk update, ready to be executed by an
 * {@link UpdateDriver}.
 * <p>
 * This corresponds to one {@link works.bosk.BoskDriver} update call, not
 * necessarily to one MongoDB update command. Executing it may issue any mix of
 * database operations—updates, replacements, inserts, deletes, and reads—
 * depending on the format and the operation kind.
 * <p>
 * The format driver decides which kind of operation to produce, because only it
 * knows how many documents the update touches and whether its preconditions span
 * documents. The {@link UpdateDriver} owns the session and transaction lifecycle,
 * so the operation itself never creates or commits a session or transaction.
 * <p>
 * Operations are executed while the current thread's {@link SessionCollection}
 * session is active. Both kinds of operation receive the live collection, which
 * is bound to that session; they must not capture a collection from another
 * thread or from an earlier operation.
 */
sealed interface UpdateOperation permits AtomicUpdateOperation, TransactionalUpdateOperation {
}
