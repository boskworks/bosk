package works.bosk.drivers.mongo.internal;

/**
 * Lets tests interpose on the closing of a {@link TransactionalCollection.Session}.
 * <p>
 * {@link TransactionalCollection.Session#close()} aborts an active transaction
 * before closing the client session, but the MongoDB driver's own
 * {@code ClientSession.close()} also aborts an active transaction. The client
 * session must therefore be closed even if the explicit abort fails (for example,
 * if the closing thread is interrupted); this interceptor lets a test force the
 * abort to fail and verify that the client session is still closed.
 * <p>
 * Installed via {@link TestProbes#withSessionInterceptor(SessionInterceptor)}.
 */
@FunctionalInterface
public interface SessionInterceptor {
	/**
	 * Invoked immediately before aborting an active transaction.
	 * Throwing simulates an abort that failed.
	 */
	void beforeAbortAttempt();

	static SessionInterceptor identity() {
		return () -> {};
	}
}
