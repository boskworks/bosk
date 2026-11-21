package works.bosk.load;

/**
 * When the harness calls {@link works.bosk.BoskDriver#flush()}.
 */
public enum FlushMode {
	/**
	 * Flush after every update.
	 */
	EVERY_OP,

	/**
	 * Flush after every {@link LoadConfig#flushEvery()} updates, per worker.
	 */
	EVERY_N,

	/**
	 * Do not flush during the run; flush once after the workers stop.
	 */
	END_ONLY
}
