package works.bosk.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskInfo;
import works.bosk.Phantom;
import works.bosk.ReferenceUtils;
import works.bosk.bytecode.ClassBuilder;
import works.bosk.bytecode.LocalVariable;
import works.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;
import static works.bosk.ReferenceUtils.getterMethod;
import static works.bosk.StateTreeSerializer.isImplicitParameter;
import static works.bosk.bytecode.ClassBuilder.here;

@RequiredArgsConstructor
final class JacksonCompiler {
	private final JacksonSerializer jacksonSerializer;

	/**
	 * A stack of types for which we are in the midst of compiling a {@link CompiledSerDes}.
	 *
	 * <p>
	 * Compiling for a particular node type recursively triggers compilations for the
	 * node's fields. This stack tracks those compilations to avoid infinite recursion
	 * for recursive datatypes.
	 */
	private final ThreadLocal<Deque<JavaType>> compilationsInProgress = ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * The main entry point to the compiler.
	 * Only for ordinary {@link works.bosk.StateTreeNode}s that aren't variants.
	 *
	 * @return a newly compiled {@link CompiledSerDes} for values of the given <code>nodeType</code>.
	 */
	public <T> CompiledSerDes<T> compiled(JavaType nodeType, BoskInfo<?> boskInfo) {
		LOGGER.debug("Compiling SerDes for node type {}", nodeType);
		try {
			// Record that we're compiling this one to avoid infinite recursion
			compilationsInProgress.get().addLast(nodeType);

			// Grab some required info about the node class
			@SuppressWarnings("unchecked")
			Class<T> nodeClass = (Class<T>) nodeType.getRawClass();
			Constructor<?> constructor = ReferenceUtils.getCanonicalConstructor(nodeClass);
			List<RecordComponent> components = asList(nodeClass.getRecordComponents());

			// Generate the Codec class and instantiate it
			ClassBuilder<Codec> cb = new ClassBuilder<>("BOSK_JACKSON_" + nodeClass.getSimpleName(), JacksonCodecRuntime.class, nodeClass.getClassLoader(), here());
			cb.beginClass();

			generate_writeFields(nodeType, components, cb);
			generate_instantiateFrom(constructor, components, cb);

			Codec codec = cb.buildInstance();

			// Return a CodecWrapper for the codec
			LinkedHashMap<String, RecordComponent> componentsByName = new LinkedHashMap<>();
			components.forEach(p -> componentsByName.put(p.getName(), p));
			return new CodecWrapper<>(codec, boskInfo, nodeType, componentsByName);
		} finally {
			Type removed = compilationsInProgress.get().removeLast();
			assert removed.equals(nodeType);
		}
	}

	/**
	 * The output of {@link JacksonCompiler#compiled}.
	 * Packages a {@link JsonSerializer} and a {@link JsonDeserializer}.
	 */
	interface CompiledSerDes<T> {
		JsonSerializer<T> serializer(SerializationConfig config);
		JsonDeserializer<T> deserializer(DeserializationConfig config);
	}

	/**
	 * The interface to the actual compiled code for a given type.
	 */
	interface Codec {
		/**
		 * Send all fields of <code>node</code> to the given <code>jsonWriter</code>
		 * as name+value pairs.
		 *
		 * @return Nothing. {@link ClassBuilder} does not yet support void methods.
		 */
		Object writeFields(Object node, JsonGenerator jsonGenerator, SerializerProvider serializers) throws IOException;

		/**
		 * A faster version of {@link Constructor#newInstance} without the overhead
		 * of checking for errors that we know can't happen.
		 */
		Object instantiateFrom(List<Object> parameterValues) throws IOException;
	}

	/**
	 * Generates the body of the {@link Codec#writeFields} method.
	 */
	private void generate_writeFields(Type nodeType, List<RecordComponent> components, ClassBuilder<Codec> cb) {
		JavaType nodeJavaType = TypeFactory.defaultInstance().constructType(nodeType);
		Class<?> nodeClass = nodeJavaType.getRawClass();
		cb.beginMethod(CODEC_WRITE_FIELDS);
		// Incoming arguments
		final LocalVariable node = cb.parameter(1);
		final LocalVariable jsonGenerator = cb.parameter(2);
		final LocalVariable serializers = cb.parameter(3);

		for (RecordComponent component : components) {
			if (isImplicitParameter(nodeClass, component)) {
				continue;
			}
			if (Phantom.class.isAssignableFrom(component.getType())) {
				continue;
			}

			String name = component.getName();

			// Build a FieldWritePlan
			// Maintenance note: resist the urge to put case-specific intelligence into
			// building the plan. The plan should be straightforward and "obviously
			// correct". The execution of the plan should contain the sophistication.
			FieldWritePlan plan;
			JavaType parameterType = TypeFactory.defaultInstance().resolveMemberType(component.getGenericType(), nodeJavaType.getBindings());
			plan = new OrdinaryFieldWritePlan();
			if (Optional.class.isAssignableFrom(component.getType())) {
				plan = new OptionalFieldWritePlan(plan);
			}

			LOGGER.debug("FieldWritePlan for {}.{}: {}", nodeClass.getSimpleName(), name, plan);

			// Put the field value on the operand stack
			cb.pushLocal(node);
			cb.castTo(nodeClass);
			try {
				cb.invoke(getterMethod(nodeClass, name));
			} catch (InvalidTypeException e) {
				throw new AssertionError("Should be impossible for a type that has already been validated", e);
			}

			// Execute the plan
			SerializerProvider serializerProvider = null; // static optimization not yet implemented
			plan.generateFieldWrite(name, cb, jsonGenerator, serializers, serializerProvider, parameterType);
		}
		// TODO: Support void methods
		cb.pushLocal(node);
		cb.finishMethod();
	}

	/**
	 * Generates the body of the {@link Codec#instantiateFrom} method.
	 */
	private void generate_instantiateFrom(Constructor<?> constructor, List<RecordComponent> components, ClassBuilder<Codec> cb) {
		cb.beginMethod(CODEC_INSTANTIATE_FROM);

		// Save incoming operand to local variable
		final LocalVariable parameterValues = cb.parameter(1);

		// New object
		cb.instantiate(constructor.getDeclaringClass());

		// Push components and invoke constructor
		cb.dup();
		for (int i = 0; i < components.size(); i++) {
			cb.pushLocal(parameterValues);
			cb.pushInt(i);
			cb.invoke(LIST_GET);
			cb.castTo(components.get(i).getType());
		}
		cb.invoke(constructor);

		cb.finishMethod();
	}

	/**
	 * This is the building block of compiler's "intermediate form" describing
	 * how to write a single field to Jackson.
	 *
	 * <p>
	 * Evolution note: this is a vestigial feature used in the past to deal with
	 * the combinatorial explosion of possibilities that no longer exist.
	 * This should probably be refactored away.
	 */
	private interface FieldWritePlan {
		/**
		 * Emit code that writes the given field's name and value to a {@link JsonGenerator}.
		 * The value is required to be on the operand stack at the start of the generated sequence.
		 *
		 * <p>
		 * Some implementations will be stackable modifiers that perhaps emit some code,
		 * then delegate to some downstream <code>generateFieldWrite</code> method, possibly
		 * with modified parameters.
		 */
		void generateFieldWrite(
			String name,
			ClassBuilder<Codec> cb,
			LocalVariable jsonGenerator,
			LocalVariable serializers,
			SerializerProvider serializerProvider,
			JavaType type);
	}

	/**
	 * The basic, un-optimized, canonical way to write a field.
	 */
	private record OrdinaryFieldWritePlan() implements FieldWritePlan {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, LocalVariable jsonGenerator, LocalVariable serializers, SerializerProvider serializerProvider, JavaType type) {
			cb.pushString(name);
			cb.pushObject("type", type, JavaType.class);
			cb.pushLocal(jsonGenerator);
			cb.pushLocal(serializers);
			cb.invoke(DYNAMIC_WRITE_FIELD);
		}
	}

	/**
	 * A stackable wrapper that writes an <code>{@link Optional}&lt;T&gt;</code> given
	 * a {@link FieldWritePlan} for <code>T</code>.
	 *
	 * @param valueWriter Handles the value inside the {@link Optional}.
	 */
	private record OptionalFieldWritePlan(
		FieldWritePlan valueWriter
	) implements FieldWritePlan {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, LocalVariable jsonGenerator, LocalVariable serializers, SerializerProvider serializerProvider, JavaType type) {
			cb.castTo(Optional.class);
			LocalVariable optional = cb.popToLocal();
			cb.pushLocal(optional);
			cb.invoke(OPTIONAL_IS_PRESENT);
			cb.ifTrue(() -> {
				// Unwrap
				cb.pushLocal(optional);
				cb.invoke(OPTIONAL_GET);

				// Write the value
				valueWriter.generateFieldWrite(name, cb, jsonGenerator, serializers, serializerProvider,
					JacksonSerializer.javaParameterType(type, Optional.class, 0));
			});
		}
	}

	/**
	 * Implements the {@link CompiledSerDes} interface using a {@link Codec} object.
	 * Putting boilerplate code in this wrapper is much easier than generating it
	 * in the compiler, and allows us to keep the {@link Codec} interface focused
	 * on just the highly-customized code that we do want to generate.
	 */
	@Value
	@EqualsAndHashCode(callSuper = false)
	private class CodecWrapper<T> implements CompiledSerDes<T> {
		Codec codec;
		BoskInfo<?> boskInfo;
		JavaType nodeJavaType;
		LinkedHashMap<String, RecordComponent> componentsByName;

		@Override
		public JsonSerializer<T> serializer(SerializationConfig config) {
			return new JsonSerializer<>() {
				@Override
				public void serialize(T value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();
					codec.writeFields(value, gen, serializers);
					gen.writeEndObject();
				}
			};
		}

		@Override
		public JsonDeserializer<T> deserializer(DeserializationConfig config) {
			return new JsonDeserializer<>() {
				@Override
				public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					// Performance-critical. Pre-compute as much as possible outside this method.
					// Note: the reading side can't be as efficient as the writing side
					// because we need to tolerate the fields arriving in arbitrary order.
					Map<String, Object> valueMap = jacksonSerializer.gatherParameterValuesByName(nodeJavaType, componentsByName, p, ctxt);

					List<Object> parameterValues = jacksonSerializer.parameterValueList(nodeJavaType.getRawClass(), valueMap, componentsByName, boskInfo);

					@SuppressWarnings("unchecked")
					T result = (T) codec.instantiateFrom(parameterValues);
					return result;
				}
			};
		}
	}

	private static final Method CODEC_WRITE_FIELDS, CODEC_INSTANTIATE_FROM;
	private static final Method DYNAMIC_WRITE_FIELD;
	private static final Method LIST_GET;
	private static final Method OPTIONAL_IS_PRESENT, OPTIONAL_GET;

	static {
		try {
			CODEC_WRITE_FIELDS = Codec.class.getDeclaredMethod("writeFields", Object.class, JsonGenerator.class, SerializerProvider.class);
			CODEC_INSTANTIATE_FROM = Codec.class.getDeclaredMethod("instantiateFrom", List.class);
			DYNAMIC_WRITE_FIELD = JacksonCodecRuntime.class.getDeclaredMethod("dynamicWriteField", Object.class, String.class, JavaType.class, JsonGenerator.class, SerializerProvider.class);
			LIST_GET = List.class.getDeclaredMethod("get", int.class);
			OPTIONAL_IS_PRESENT = Optional.class.getDeclaredMethod("isPresent");
			OPTIONAL_GET = Optional.class.getDeclaredMethod("get");
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(JacksonCompiler.class);

}
