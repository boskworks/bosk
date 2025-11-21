/**
 * Serializer implementation that uses the Jackson library to convert Bosk objects to and from JSON.
 * <p>
 * See {@link works.bosk.jackson.JacksonSerializer} for the main entry point.
 */
module works.bosk.jackson {
	requires transitive tools.jackson.core;
	requires transitive tools.jackson.databind;
	requires org.slf4j;
	requires transitive works.bosk.core;

	requires static lombok;

	exports works.bosk.jackson;
}
