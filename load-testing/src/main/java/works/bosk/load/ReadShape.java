package works.bosk.load;

import java.util.Locale;

/**
 * What each reader thread reads from the bosk.
 */
public enum ReadShape {
	/**
	 * A single string field of one entity.
	 */
	LEAF,

	/**
	 * One whole entity, including any sub-objects.
	 */
	ENTITY,

	/**
	 * The whole root catalog.
	 */
	CATALOG;

	static ReadShape fromCli(String value) {
		return switch (value.toLowerCase(Locale.ROOT).replace("_", "")) {
			case "leaf", "string" -> LEAF;
			case "entity" -> ENTITY;
			case "catalog" -> CATALOG;
			default -> throw new IllegalArgumentException("Unknown read shape \"" + value + "\"");
		};
	}
}
