package works.bosk.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import works.bosk.jackson.JacksonCompiler.Codec;

/**
 * <strong>This is not part of the public API.</strong>
 * This class must be public so it can be the superclass of our dynamically
 * generated classes.
 */
public abstract class JacksonCodecRuntime implements Codec {
	/**
	 * Looks up a {@link ValueSerializer} at serialization time, and uses it to {@link ValueSerializer#serialize} serialize} the given field.
	 *
	 * <p>
	 * This is the basic, canonical way to write fields, but usually we can optimize
	 * this by looking up the {@link ValueSerializer} ahead of time, while compiling the
	 * codec, so we can save the overhead of the lookup operation during serialization.
	 */
	protected static void dynamicWriteField(
		Object fieldValue,
		String fieldName,
		JavaType type,
		JsonGenerator gen,
		SerializationContext serializers
	) {
		gen.writeName(fieldName);
		serializers
			.findValueSerializer(type)
			.serialize(fieldValue, gen, serializers);
	}

}
