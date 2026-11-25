package works.bosk.load;

import java.util.Locale;

/**
 * The scope of the hook registered by a load run.
 */
public enum HookScope {
	/**
	 * One notification per update, since the root changes on every update.
	 */
	ROOT,

	/**
	 * One notification per updated entity, with up to one pending path per entity.
	 */
	ENTITY;

	static HookScope fromCli(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "root" -> ROOT;
			case "entity" -> ENTITY;
			default -> throw new IllegalArgumentException("Unknown hook scope \"" + value + "\"");
		};
	}
}
