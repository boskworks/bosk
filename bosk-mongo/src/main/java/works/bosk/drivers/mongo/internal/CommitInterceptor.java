package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;

/**
 * Lets tests interpose on transaction commits. Each commit attempt in
 * {@link TransactionalCollection.Session#commitTransactionIfAny()} runs the actual commit
 * and then invokes this interceptor, which may throw to simulate a failed commit.
 * Throwing a {@link MongoException} with the
 * {@link MongoException#UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL} error label causes the
 * commit to be retried, so tests can exercise the retry logic.
 * <p>
 * The interceptor runs <em>after</em> the actual commit attempt, not before, because the
 * MongoDB driver's own transaction state machine must run first: the driver marks the
 * transaction committed (and {@code hasActiveTransaction()} subsequently returns
 * {@code false}) even when the commit reports an unknown result, and the retry logic
 * depends on reproducing that behaviour.
 * <p>
 * Installed via {@link TestProbes#withCommitInterceptor(CommitInterceptor)}.
 */
@FunctionalInterface
public interface CommitInterceptor {
	/**
	 * Invoked after each commit attempt, before the retry decision is made.
	 * Throwing simulates a commit that failed with the given exception.
	 */
	void afterCommitAttempt();

	static CommitInterceptor identity() {
		return () -> {};
	}
}
