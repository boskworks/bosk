package works.bosk.load;

import java.util.Locale;

/**
 * The driver under test.
 */
public enum DriverKind {
	SIMPLE,

	/**
	 * MongoDriver using the SEQUOIA format.
	 */
	MONGO_SEQUOIA,

	/**
	 * MongoDriver using the Pando format with the whole state in one document.
	 */
	MONGO_PANDO,

	/**
	 * MongoDriver using the Pando format with the root catalog grafted into separate documents.
	 */
	MONGO_PANDO_GRAFTED;

	public boolean isMongo() {
		return this != SIMPLE;
	}

	public static DriverKind fromCli(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "simple" -> SIMPLE;
			case "mongo-sequoia" -> MONGO_SEQUOIA;
			case "mongo-pando" -> MONGO_PANDO;
			case "mongo-pando-grafted", "mongo-grafted" -> MONGO_PANDO_GRAFTED;
			default -> throw new IllegalArgumentException(
				"Unsupported driver \"" + value + "\"; expected simple, mongo-sequoia, mongo-pando, or mongo-pando-grafted");
		};
	}
}
