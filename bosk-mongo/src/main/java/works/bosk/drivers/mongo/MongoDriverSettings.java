package works.bosk.drivers.mongo;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import works.bosk.BoskDriver;

import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode.EARNEST;

@Value
@Builder(toBuilder = true)
public class MongoDriverSettings {
	String database;

	/**
	 * The general responsiveness of the system. Changes to the database will be
	 * "noticed" in about this many milliseconds, and other time-related behaviours
	 * will be scaled accordingly. Lower values are more responsive, while higher
	 * values are more efficient because they'll do less polling.
	 * <p>
	 * For test cases,
	 *
	 * <ul>
	 *     <li>
	 *         If you are exercising timeout conditions, set this to a low value
	 *         (say, 1/10 of its default) to make your tests run quickly;
	 *     </li>
	 *     <li>
	 *         otherwise, set it to a high value (say, 10x its default)
	 *         to avoid spurious test failures.
	 *     </li>
	 * </ul>
	 */
	@Default int timescaleMS = 1000;

	/**
	 * @see DatabaseFormat#SEQUOIA
	 * @see PandoFormat
	 */
	@Default DatabaseFormat preferredDatabaseFormat = SEQUOIA;
	@Default InitialDatabaseUnavailableMode initialDatabaseUnavailableMode = InitialDatabaseUnavailableMode.DISCONNECT;

	@Default Experimental experimental = Experimental.builder().build();
	@Default Testing testing = Testing.builder().build();

	/**
	 * Settings with no guarantee of long-term support.
	 */
	@Value
	@Builder
	public static class Experimental {
		@Default long changeStreamInitialWaitMS = 20;
		@Default OrphanDocumentMode orphanDocumentMode = OrphanDocumentMode.HASTY;
	}

	/**
	 * Settings not meant to be used in production.
	 */
	@Value
	@Builder
	public static class Testing {
		/**
		 * How long to sleep before processing each event.
		 * If negative, sleeps before performing each driver operation
		 * so that events have a chance to arrive first.
		 */
		@Default long eventDelayMS = 0;
	}

	public sealed interface DatabaseFormat
		permits SequoiaFormat, PandoFormat
	{
		/**
		 * Simple format that stores the entire bosk state in a single document,
		 * and (except for {@link MongoDriver#refurbish() refirbish})
		 * doesn't require any multi-document transactions.
		 *
		 * <p>
		 * This limits the entire bosk state to 16MB when converted to BSON.
		 */
		DatabaseFormat SEQUOIA = new SequoiaFormat();
	}

	public static final class SequoiaFormat implements DatabaseFormat {
		SequoiaFormat() { }
		@Override public String toString() { return "SequoiaFormat"; }
	}

	public enum InitialDatabaseUnavailableMode {
		/**
		 * If the database state can't be loaded during {@link BoskDriver#initialRoot},
		 * use the downstream driver's initial state and proceed in disconnected mode.
		 * This allows the database and application to be booted in either order,
		 * which can simplify repairs and recovery in production,
		 * but during development, it can cause confusing behaviour if the database is misconfigured.
		 * <p>
		 * In the spirit of making things "just work in production", this is the default,
		 * but you might want to consider using {@link #FAIL} in non-production settings.
		 */
		DISCONNECT,

		/**
		 * If the database state can't be loaded during {@link BoskDriver#initialRoot},
		 * throw an exception.
		 * This is probably the desired "fail fast" behaviour during development,
		 * but in production, it creates a boot sequencing dependency between the application and the database.
		 */
		FAIL
	}

	public enum OrphanDocumentMode {
		/**
		 * Unused documents are always deleted before the end of the transaction.
		 */
		EARNEST,

		/**
		 * Unused documents may be left behind, to be cleaned up later.
		 */
		HASTY,
	}

	public void validate() {
		if (preferredDatabaseFormat() instanceof PandoFormat) {
			if (experimental.orphanDocumentMode() == EARNEST) {
				throw new IllegalArgumentException("Pando format does not support earnest orphan document cleanup");
			}
		}
	}

}
