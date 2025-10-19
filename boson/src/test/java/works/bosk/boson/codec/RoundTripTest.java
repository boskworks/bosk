package works.bosk.boson.codec;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeMap.Settings;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.types.DataType;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.ParameterInjector;

import static java.time.DayOfWeek.WEDNESDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.enclosingObject;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.memberValue;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.canonicalConstructor;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.constant;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.notEquals;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.recordComponent;
import static works.bosk.boson.types.DataType.STRING;

@InjectFrom({
	SettingsInjector.class,
	RoundTripTest.EscapeInjector.class,
	RoundTripTest.PrimitiveInjector.class,
	RoundTripTest.PresenceConditionInjector.class
})
public record RoundTripTest(Settings settings) {

	@InjectedTest
	void bigNumber() throws IOException {
		testRoundTrip(new BigNumberNode(BigDecimal.class), "123", new BigDecimal("123"));
	}

	@InjectedTest
	void booleans() throws IOException {
		testRoundTrip(new BooleanNode(), "false", Boolean.FALSE);
		testRoundTrip(new BooleanNode(), "true", Boolean.TRUE);
	}

	@InjectedTest
	void enumByName() throws IOException {
		var day = WEDNESDAY;
		testRoundTrip(new EnumByNameNode(DayOfWeek.class), "\"" + day.name() + "\"", day);
	}

	public record RecordWithOptionalField(String field) {}

	@InjectedTest
	void maybeAbsent(MemberPresenceCondition presenceCondition) throws IOException {
		var maybeAbsent = new MaybeAbsentSpec(
			new StringNode(),
			new ComputedSpec(constant(STRING, "default")),
			presenceCondition
			);
		var node = new FixedMapNode(
			new LinkedHashMap<>(Map.of(
				"field", new FixedMapMember(
					maybeAbsent,
					recordComponent(RecordWithOptionalField.class.getRecordComponents()[0], MethodHandles.lookup()))
			)),
			canonicalConstructor(RecordWithOptionalField.class, MethodHandles.lookup())
		);
		testRoundTrip(node, "{}", new RecordWithOptionalField("default"));
		testRoundTrip(node, "{\"field\":\"hello\"}", new RecordWithOptionalField("hello"));
	}

	@InjectedTest
	void string() throws IOException {
		var node = new StringNode();
		testRoundTrip(node, "\"Hello, world!\"", "Hello, world!");
	}

	@InjectedTest
	void stringEscapeCodes(Escape escape) throws IOException {
		testRoundTrip(new StringNode(), "\"" + escape.escaped + "\"", escape.value);
	}

	@InjectedTest
	void maybeNull() throws IOException {
		var node = new StringNode();
		testRoundTrip(new MaybeNullSpec(node), "\"hello\"", "hello");
		testRoundTrip(new MaybeNullSpec(node), "null", null);
	}

	@InjectedTest
	void primitiveNumber(Primitive p) throws IOException {
		testRoundTrip(new PrimitiveNumberNode(p.type), p.json, p.value);
	}

	/**
	 * {@link Parser#parse} can't return a primitive, so to really test them,
	 * we need to put them in a record.
	 * <p>
	 * Also acts as a test for {@link FixedMapNode}.
	 * <p>
	 * This must be public because {@link TypeScanner} can only see public methods.
	 * TODO: Add Lookup support to TypeScanner
	 */
	public record Primitives(int i, long l, float f, double d) {}

	@InjectedTest
	void primitiveRecordComponents() throws IOException {
		testRoundTrip(Primitives.class, "{\"i\":123,\"l\":123,\"f\":123.0,\"d\":123.0}", new Primitives(123, 123L, 123.0F, 123.0D));
	}

	@InjectedTest
	void boxedPrimitive(Primitive p) throws IOException {
		testRoundTrip(new BoxedPrimitiveSpec(new PrimitiveNumberNode(p.type)), p.json, p.value);
	}

	@InjectedTest
	void representAs() throws IOException {
		var node = RepresentAsSpec.as(new StringNode(),
			DataType.known(DayOfWeek.class),
			DayOfWeek::name,
			DayOfWeek::valueOf
		);
		testRoundTrip(node, "\"WEDNESDAY\"", WEDNESDAY);
	}

	private void testRoundTrip(Class<? extends Record> recordClass, String json, Object value) throws IOException {
		DataType type = DataType.of(recordClass);
		TypeScanner typeScanner = new TypeScanner(settings);
		TypeMap typeMap = typeScanner.scan(type).build();
		JsonValueSpec spec = typeMap.get(type);
		Codec codec = CodecBuilder.using(typeMap).using(MethodHandles.lookup()).build();
		var parsed = codec.parserFor(spec).parse(CharArrayJsonReader.forString(json));
		assertEquals(value, parsed);
		assertEquals(json, generateJson(codec, parsed, spec));
	}

	private void testRoundTrip(JsonValueSpec node, String json, Object value) throws IOException {
		TypeScanner typeScanner = new TypeScanner(settings);
		TypeMap typeMap = typeScanner.scan(node.dataType()).build();
		Codec codec = CodecBuilder.using(typeMap).build(node);
		var parsed = codec.parserFor(node).parse(CharArrayJsonReader.forString(json));
		assertEquals(value, parsed);
		assertEquals(json, generateJson(codec, parsed, node));
	}

	private static String generateJson(Codec codec, Object parsed, JsonValueSpec spec) {
		var writer = new CharArrayWriter();
		codec.generatorFor(spec).generate(writer, parsed);
		return writer.toString();
	}

	record Escape(String value, String escaped) {}

	record EscapeInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(Escape.class);
		}

		@Override
		public List values() {
			return Stream.of(
				new Escape("\"", "\\\""),
				new Escape("\\", "\\\\"),
				new Escape("/", "\\/"),
				new Escape("\b", "\\b"),
				new Escape("\f", "\\f"),
				new Escape("\n", "\\n"),
				new Escape("\r", "\\r"),
				new Escape("\t", "\\t"),
				new Escape("👍", "\\ud83d\\udc4d")
			).limit(1).toList();
		}
	}

	record Primitive(String name, Class<?> type, Class<?> boxedType, Object value, String json) {}

	record PrimitiveInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(Primitive.class);
		}

		@Override
		public List<Object> values() {
			return List.of(
				new Primitive("int", int.class, Integer.class, 123, "123"),
				new Primitive("long", long.class, Long.class, 123L, "123"),
				new Primitive("float", float.class, Float.class, 123.0F, "123.0"),
				new Primitive("double", double.class, Double.class, 123.0D, "123.0")
			);
		}
	}

	record PresenceConditionInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(MemberPresenceCondition.class);
		}

		@Override
		public List<Object> values() {
			// No Nullary here because it's hard to make that return different
			// values for different test cases.
			var component = RecordWithOptionalField.class.getRecordComponents()[0];
			return List.of(
				memberValue(notEquals(constant(STRING, "default"))),
				enclosingObject(notEquals(constant(STRING, "default"))
					.bind(0, recordComponent(component, MethodHandles.lookup()))
				)
			);
		}
	}
}
