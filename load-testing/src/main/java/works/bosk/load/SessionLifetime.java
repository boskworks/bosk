package works.bosk.load;

import java.util.Locale;

/**
 * How long a reader thread keeps its {@link works.bosk.Bosk#readSession() read session} open.
 *
 * <p>{@link #PER_THREAD} is the retention scenario: the session pins a state version for the whole
 * run, so the live set can grow even when the allocation rate is low.
 */
public enum SessionLifetime {
	/**
	 * Open and close a read session for every read.
	 */
	PER_OP,

	/**
	 * Keep one read session open for the whole run, per reader thread.
	 */
	PER_THREAD;

	static SessionLifetime fromCli(String value) {
		return switch (value.toLowerCase(Locale.ROOT).replace("_", "")) {
			case "perop" -> PER_OP;
			case "perthread", "longlived" -> PER_THREAD;
			default -> throw new IllegalArgumentException("Unknown session lifetime \"" + value + "\"");
		};
	}
}
