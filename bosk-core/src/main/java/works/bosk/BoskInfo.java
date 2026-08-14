package works.bosk;

import java.util.concurrent.CompletableFuture;

/**
 * Provides access to a subset of bosk functionality that is available while the
 * {@link BoskDriver} is being constructed, before the {@link Bosk} itself is fully initialized.
 */
public interface BoskInfo<R extends StateTreeNode> {
	String name();
	Identifier instanceID();
	RootReference<R> rootReference();
	BoskContext context();

	/**
	 * A future that completes with this {@link Bosk} once it has been fully
	 * initialized and is ready to accept updates from drivers. Waiting on it
	 * yields a bosk whose final fields are all initialized and whose in-memory
	 * state tree is populated, so a driver that processes updates asynchronously
	 * can be sure the bosk is ready to receive them.
	 * <p>
	 * If the {@link Bosk} constructor fails, the future completes exceptionally.
	 */
	CompletableFuture<Bosk<R>> boskFuture();

	/**
	 * The {@link Bosk} once it has been fully initialized.
	 *
	 * @throws IllegalStateException if called before the {@link Bosk} constructor
	 * has finished; use {@link #boskFuture()} to wait for initialization to complete.
	 */
	default Bosk<R> bosk() {
		CompletableFuture<Bosk<R>> future = boskFuture();
		if (future.isDone()) {
			return future.join();
		} else {
			throw new IllegalStateException("Bosk is not yet initialized");
		}
	}

}
