package works.bosk.jackson;

public record JacksonSerializerConfiguration(
	MapShape mapShape
) {
	public static JacksonSerializerConfiguration defaultConfiguration() {
		return new JacksonSerializerConfiguration(MapShape.ARRAY);
	}

	/**
	 * How bosk's ordered maps should translate to JSON, where the order of object
	 * fields is generally not preserved.
	 */
	public enum MapShape {
		/**
		 * A shape intended for brevity, human readability, and efficient serialization/deserialization.
		 * <p>
		 * An array of single-field objects, where the field name is the map entry's key
		 * and the field value is the map entry's value.
		 */
		ARRAY,

		/**
		 * A shape intended for efficient incremental modification,
		 * especially for values stored in a database that supports JSON
		 * but does not preserve object field order (like Postgresql).
		 * Inspired by {@link java.util.LinkedHashMap LinkedHashMap}.
		 * <p>
		 * An object containing the natural keys and values of the map being stored,
		 * with a few changes:
		 *
		 * <ul>
		 *     <li>
		 *         The object also has fields "{@code -first}" and "{@code -last}" pointing
		 *         at the first and last map entries, so they can be found efficiently.
		 *         (These names have leading dashes to distinguish them from valid
		 *         {@link works.bosk.Identifier Identifier} values.)
		 *     </li>
		 *     <li>
		 *         The object's field values are themselves objects defined by
		 *         {@link JacksonSerializer.LinkedMapEntry LinkedMapEntry}.
		 *     </li>
		 * </ul>
		 *
		 * The resulting structure supports the following operations in O(1) time:
		 * <ul>
		 *     <li>
		 *         Lookup an entry given its ID
		 *     </li>
		 *     <li>
		 *         Add a new entry at the end.
		 *     </li>
		 *     <li>
		 *         Delete an entry.
		 *     </li>
		 * </ul>
		 *
		 * It also supports linear time walking of the entries in both forward and reverse order.
		 */
		LINKED_MAP,
	}
}
