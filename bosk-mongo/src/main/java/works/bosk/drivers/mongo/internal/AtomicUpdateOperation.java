package works.bosk.drivers.mongo.internal;

import java.util.Optional;

/**
 * An update that first attempts a single logical document write in a
 * retryable-write session.
 * <p>
 * If the attempt completes, the operation returns {@link Optional#empty()}.
 * If the attempt applies no write and cannot complete without a transaction,
 * it returns a {@link TransactionalUpdateOperation} that the driver runs
 * once, in a transaction, on the same session.
 * <p>
 * The operation must return a fallback only if the attempt applied no write.
 * The fallback must re-check its conditions inside the transaction, because
 * another writer may change the database between the attempt and the fallback.
 * <p>
 * The operation must be safe for the client library to retry as a retryable
 * write. In practice this means the attempt must consist of a single database
 * write to a document identified by {@code _id}, with no read-then-write
 * decision that would be invalid on a second attempt.
 */
@FunctionalInterface
non-sealed interface AtomicUpdateOperation extends UpdateOperation {
	Optional<TransactionalUpdateOperation> execute(SessionCollection collection);
}
