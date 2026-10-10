package works.bosk.jackson;

import tools.jackson.core.JsonGenerator;
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
	 * Writes a field by looking up the value's {@link ValueSerializer} at serialization
	 * time, based on the value's runtime type, and using it to {@link ValueSerializer#serialize serialize}
	 * the value.
	 *
	 * <p>
	 * Serialization is value-driven: Jackson picks each value's serializer from the value
	 * itself, so the field's declared type tells us nothing useful and isn't consulted.
	 */
	protected static void dynamicWriteField(
		Object fieldValue,
		String fieldName,
		JsonGenerator gen,
		SerializationContext serializers
	) {
		gen.writeName(fieldName);
		if (fieldValue == null) {
			gen.writeNull();
		} else {
			serializers.findValueSerializer(fieldValue.getClass()).serialize(fieldValue, gen, serializers);
		}
	}

}
