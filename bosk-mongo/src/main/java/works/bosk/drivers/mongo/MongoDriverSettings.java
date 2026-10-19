package works.bosk.drivers.mongo;

import java.util.Objects;
import works.bosk.Bosk;
import works.bosk.BoskDriver;

import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.DISCONNECT;
import static works.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode.EARNEST;
import static works.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode.HASTY;

public final class MongoDriverSettings {
	private final String database;

	/**
	 * The name of the collection within the {@link #database} in which the bosk state
	 * is stored. Bosks that use the same database and collection share the same state,
	 * so the two names together identify a replication group. Using a different
	 * collection name lets a bosk coexist in a database alongside the application's own
	 * collections, or alongside an unrelated bosk.
	 * <p>
	 * The default is {@link #DEFAULT_COLLECTION_NAME}.
	 */
	private final String collection;

	/**
	 * The general responsiveness of the system under unusual circumstances.
	 * Changes to the database connectivity and status will be "noticed"
	 * in about this many milliseconds, and other time-related behaviours
	 * are scaled accordingly.
	 * Among other things, this is the interval at which a change stream polls
	 * the database when idle, so it also governs each bosk's idle network traffic.
	 * Lower values recover more quickly, but will also give up sooner
	 * and cause more network traffic;
	 * higher values are more patient and efficient, and can tolerate
	 * longer network disruptions, but are slower to recover.
	 * <p>
	 * Under normal circumstances, the system responds promptly
	 * and efficiently regardless of this setting.
	 * <p>
	 * For test cases,
	 *
	 * <ul>
	 *     <li>
	 *         If you are exercising timeout conditions, set this to a low value
	 *         (say, 10x the expected network round-trip delay)
	 *         to make your tests run quickly;
	 *     </li>
	 *     <li>
	 *         otherwise, you can set it to a high value (say, 5x its default)
	 *         to avoid spurious test failures,
	 *         though the default should also be fine.
	 *     </li>
	 * </ul>
	 *
	 * In Bosk's own tests, we use even more extreme values to ensure
	 * this works across a wide range of values.
	 */
	private final int timescaleMS;

	/**
	 * @see DatabaseFormat#SEQUOIA
	 * @see PandoFormat
	 */
	private final DatabaseFormat preferredDatabaseFormat;

	/**
	 * Default is {@link InitialDatabaseUnavailableMode#DISCONNECT DISCONNECT}
	 * because it simplifies production deployments and repairs,
	 * but these very fault-tolerance features can be confusing during development.
	 */
	private final InitialDatabaseUnavailableMode initialDatabaseUnavailableMode;
	private final Experimental experimental;
	private final Testing testing;

	private MongoDriverSettings(
		String database,
		String collection,
		int timescaleMS,
		DatabaseFormat preferredDatabaseFormat,
		InitialDatabaseUnavailableMode initialDatabaseUnavailableMode,
		Experimental experimental,
		Testing testing
	) {
		this.database = database;
		this.collection = collection;
		this.timescaleMS = timescaleMS;
		this.preferredDatabaseFormat = preferredDatabaseFormat;
		this.initialDatabaseUnavailableMode = initialDatabaseUnavailableMode;
		this.experimental = experimental;
		this.testing = testing;
	}

	public static MongoDriverSettingsBuilder builder() {
		return new MongoDriverSettingsBuilder();
	}

	public MongoDriverSettingsBuilder toBuilder() {
		return new MongoDriverSettingsBuilder()
			.database(database)
			.collection(collection)
			.timescaleMS(timescaleMS)
			.preferredDatabaseFormat(preferredDatabaseFormat)
			.initialDatabaseUnavailableMode(initialDatabaseUnavailableMode)
			.experimental(experimental)
			.testing(testing);
	}

	public String database() {
		return database;
	}

	public String collection() {
		return collection;
	}

	public int timescaleMS() {
		return timescaleMS;
	}

	public DatabaseFormat preferredDatabaseFormat() {
		return preferredDatabaseFormat;
	}

	public InitialDatabaseUnavailableMode initialDatabaseUnavailableMode() {
		return initialDatabaseUnavailableMode;
	}

	public Experimental experimental() {
		return experimental;
	}

	public Testing testing() {
		return testing;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		} else if (o instanceof MongoDriverSettings other) {
			return Objects.equals(database, other.database)
				&& Objects.equals(collection, other.collection)
				&& timescaleMS == other.timescaleMS
				&& Objects.equals(preferredDatabaseFormat, other.preferredDatabaseFormat)
				&& initialDatabaseUnavailableMode == other.initialDatabaseUnavailableMode
				&& Objects.equals(experimental, other.experimental)
				&& Objects.equals(testing, other.testing);
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(database, collection, timescaleMS, preferredDatabaseFormat, initialDatabaseUnavailableMode, experimental, testing);
	}

	@Override
	public String toString() {
		return "MongoDriverSettings("
			+ "database=" + database
			+ ", collection=" + collection
			+ ", timescaleMS=" + timescaleMS
			+ ", preferredDatabaseFormat=" + preferredDatabaseFormat
			+ ", initialDatabaseUnavailableMode=" + initialDatabaseUnavailableMode
			+ ", experimental=" + experimental
			+ ", testing=" + testing
			+ ")";
	}

	public static final class MongoDriverSettingsBuilder {
		private String database;
		private String collection = DEFAULT_COLLECTION_NAME;
		private int timescaleMS = 10_000;
		private DatabaseFormat preferredDatabaseFormat = SEQUOIA;
		private InitialDatabaseUnavailableMode initialDatabaseUnavailableMode = DISCONNECT;
		private Experimental experimental = Experimental.builder().build();
		private Testing testing = Testing.builder().build();

		private MongoDriverSettingsBuilder() { }

		public MongoDriverSettingsBuilder database(String database) {
			this.database = database;
			return this;
		}

		public MongoDriverSettingsBuilder collection(String collection) {
			this.collection = collection;
			return this;
		}

		public MongoDriverSettingsBuilder timescaleMS(int timescaleMS) {
			this.timescaleMS = timescaleMS;
			return this;
		}

		public MongoDriverSettingsBuilder preferredDatabaseFormat(DatabaseFormat preferredDatabaseFormat) {
			this.preferredDatabaseFormat = preferredDatabaseFormat;
			return this;
		}

		public MongoDriverSettingsBuilder initialDatabaseUnavailableMode(InitialDatabaseUnavailableMode initialDatabaseUnavailableMode) {
			this.initialDatabaseUnavailableMode = initialDatabaseUnavailableMode;
			return this;
		}

		public MongoDriverSettingsBuilder experimental(Experimental experimental) {
			this.experimental = experimental;
			return this;
		}

		public MongoDriverSettingsBuilder testing(Testing testing) {
			this.testing = testing;
			return this;
		}

		public MongoDriverSettings build() {
			return new MongoDriverSettings(database, collection, timescaleMS, preferredDatabaseFormat, initialDatabaseUnavailableMode, experimental, testing);
		}
	}

	/**
	 * Settings with no guarantee of long-term support.
	 */
	public static final class Experimental {
		private final OrphanDocumentMode orphanDocumentMode;

		private Experimental(OrphanDocumentMode orphanDocumentMode) {
			this.orphanDocumentMode = orphanDocumentMode;
		}

		public static ExperimentalBuilder builder() {
			return new ExperimentalBuilder();
		}

		public OrphanDocumentMode orphanDocumentMode() {
			return orphanDocumentMode;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Experimental other && orphanDocumentMode == other.orphanDocumentMode;
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(orphanDocumentMode);
		}

		@Override
		public String toString() {
			return "Experimental(orphanDocumentMode=" + orphanDocumentMode + ")";
		}

		public static final class ExperimentalBuilder {
			private OrphanDocumentMode orphanDocumentMode = HASTY;

			private ExperimentalBuilder() { }

			public ExperimentalBuilder orphanDocumentMode(OrphanDocumentMode orphanDocumentMode) {
				this.orphanDocumentMode = orphanDocumentMode;
				return this;
			}

			public Experimental build() {
				return new Experimental(orphanDocumentMode);
			}
		}
	}

	/**
	 * Settings not meant to be used in production.
	 */
	public static final class Testing {
		/**
		 * How long to sleep before processing each event.
		 * If negative, sleeps before performing each driver operation
		 * so that events have a chance to arrive first.
		 */
		private final long eventDelayMS;

		private Testing(long eventDelayMS) {
			this.eventDelayMS = eventDelayMS;
		}

		public static TestingBuilder builder() {
			return new TestingBuilder();
		}

		public long eventDelayMS() {
			return eventDelayMS;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Testing other && eventDelayMS == other.eventDelayMS;
		}

		@Override
		public int hashCode() {
			return Long.hashCode(eventDelayMS);
		}

		@Override
		public String toString() {
			return "Testing(eventDelayMS=" + eventDelayMS + ")";
		}

		public static final class TestingBuilder {
			private long eventDelayMS = 0;

			private TestingBuilder() { }

			public TestingBuilder eventDelayMS(long eventDelayMS) {
				this.eventDelayMS = eventDelayMS;
				return this;
			}

			public Testing build() {
				return new Testing(eventDelayMS);
			}
		}
	}

	public sealed interface DatabaseFormat
		permits SequoiaFormat, PandoFormat
	{
		String name();

		/**
		 * Simple format that stores the entire bosk state in a single document,
		 * and (except for {@link MongoDriver#refurbish() refurbish})
		 * doesn't require any multi-document transactions.
		 *
		 * <p>
		 * This limits the entire bosk state to 16MB when converted to BSON.
		 */
		DatabaseFormat SEQUOIA = new SequoiaFormat();
	}

	public static final class SequoiaFormat implements DatabaseFormat {
		SequoiaFormat() { }
		@Override public String name() { return "Sequoia"; }
		@Override public String toString() { return "SequoiaFormat"; }
	}

	public enum InitialDatabaseUnavailableMode {
		/**
		 * If the database state can't be loaded during {@link BoskDriver#initialState},
		 * use the downstream driver's initial state and proceed in disconnected mode.
		 * This allows the database and application to be booted in either order,
		 * which can simplify repairs and recovery in production,
		 * but during development, it can cause confusing behaviour if the database is misconfigured.
		 * <p>
		 * In the spirit of making things "just work in production", this is the default,
		 * but you might want to consider using {@link #FAIL_FAST} in non-production settings.
		 */
		DISCONNECT,

		/**
		 * If the database state can't be loaded during {@link BoskDriver#initialState},
		 * throw an exception from the {@link Bosk#Bosk Bosk constructor} call.
		 * This is probably the desired behaviour during development,
		 * but in production, it creates a boot sequencing dependency between the application and the database.
		 */
		FAIL_FAST
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
		if (database == null || database.isBlank()) {
			throw new IllegalArgumentException("Database name must not be blank");
		}
		if (collection == null || collection.isBlank()) {
			throw new IllegalArgumentException("Collection name must not be blank");
		}
		if (preferredDatabaseFormat() instanceof PandoFormat) {
			if (experimental.orphanDocumentMode() == EARNEST) {
				throw new IllegalArgumentException("Pando format does not support earnest orphan document cleanup");
			}
		}
	}

	/**
	 * The default value of {@link #collection()}.
	 */
	public static final String DEFAULT_COLLECTION_NAME = "boskCollection";

}
