package works.bosk.boson.codec;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.boson.codec.PrimitiveInjector.PrimitiveNumber;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeMap.Settings;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.FixedObjectNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.RecognizedMember;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.mapping.spec.handles.ObjectAccumulator;
import works.bosk.boson.mapping.spec.handles.ObjectEmitter;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.TypeReference;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.ParameterInjector;

import static java.time.DayOfWeek.WEDNESDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.enclosingObject;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.memberValue;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.canonicalConstructor;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.constant;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.notEquals;
import static works.bosk.boson.types.DataType.BOOLEAN;
import static works.bosk.boson.types.DataType.INT;
import static works.bosk.boson.types.DataType.STRING;

@InjectFrom({
	RoundTripTest.EscapeInjector.class,
	PrimitiveInjector.class,
	RoundTripTest.PresenceConditionInjector.class
})
@ParameterizedClass
@MethodSource("settings")
@TestInstance(PER_METHOD)
public final class RoundTripTest {
	final Settings settings;
	final MethodHandles.Lookup lookup = MethodHandles.lookup();

	public RoundTripTest(Settings settings) {
		this.settings = settings;
	}

	static List<Settings> settings() {
		return new SettingsInjector().values();
	}

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
		var node = new FixedObjectNode(
			new LinkedHashMap<>(Map.of(
				"field", new RecognizedMember(
					maybeAbsent,
					TypedHandles.componentAccessor(RecordWithOptionalField.class.getRecordComponents()[0], lookup))
			)),
			canonicalConstructor(RecordWithOptionalField.class, lookup)
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
	void primitiveNumber(PrimitiveNumber p) throws IOException {
		testRoundTrip(new PrimitiveNumberNode(p.type()), p.json(), p.value());
	}

	/**
	 * {@link Parser#parse} can't return a primitive, so to really test them,
	 * we need to put them in a record.
	 * <p>
	 * Also acts as a test for {@link FixedObjectNode}.
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
	void boxedPrimitive(PrimitiveNumber p) throws IOException {
		testRoundTrip(new BoxedPrimitiveSpec(new PrimitiveNumberNode(p.type())), p.json(), p.value());
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

	@InjectedTest
	void uniformMapUsingIterator() throws IOException {
		BoundType linkedHashMapType = (BoundType) DataType.of(new TypeReference<LinkedHashMap<String, Integer>>() {});
		var node = new UniformMapNode(
			new StringNode(),
			new BoxedPrimitiveSpec(new PrimitiveNumberNode(int.class)),
			TypeScanner.mapAccumulator(linkedHashMapType),
			TypeScanner.mapEmitter(linkedHashMapType)
		);
		testRoundTrip(node, """
			{"one":1,"two":2,"three":3}""",
			new LinkedHashMap<>(Map.of(
				"one", 1,
				"two", 2,
				"three", 3
			))
		);
	}

	@InjectedTest
	void uniformMapUsingForLoop() throws IOException, NoSuchMethodException, IllegalAccessException {
		// This one is contrived: we represent an integer (say 3) as a map of the form:
		// {"1":1,"2":2,"3":3}
		// While parsing, the int is simply taken to be the greatest member value.

		ObjectAccumulator accumulator = new ObjectAccumulator(
			constant(INT, 0),
			new TypedHandle(
				MethodHandles.lookup().findStatic(RoundTripTest.class, "accumulateMax", MethodType.methodType(int.class, int.class, String.class, int.class)),
				INT, List.of(INT, STRING, INT)
			),
			TypedHandles.identity(INT)
		);
		ObjectEmitter emitter = new ObjectEmitter(
			constant(INT, 1).dropArguments(0, INT),
			new TypedHandle(
				MethodHandles.lookup().findStatic(RoundTripTest.class, "emitterHasNext", MethodType.methodType(boolean.class, int.class, int.class)),
				BOOLEAN, List.of(INT, INT)
			),
			new TypedHandle(
				MethodHandles.lookup().findStatic(RoundTripTest.class, "emitterNext", MethodType.methodType(int.class, int.class, int.class)),
				INT, List.of(INT, INT)
			),
			new TypedHandle(
				MethodHandles.lookup().findStatic(Integer.class, "toString", MethodType.methodType(String.class, int.class)),
				STRING, List.of(INT)
			),
			TypedHandles.identity(INT)
		);
		var node = new UniformMapNode(
			new StringNode(),
			new PrimitiveNumberNode(int.class),
			accumulator,
			emitter
		);
		testRoundTrip(node, """
			{"1":1,"2":2,"3":3}""",
			3
		);
		testRoundTrip(node, "{}", 0);
	}

	private static int accumulateMax(int currentMax, String key, int value) {
		return Math.max(currentMax, value);
	}

	private static boolean emitterHasNext(int currentValue, int maxValue) {
		return currentValue <= maxValue;
	}

	private static int emitterNext(int currentValue, int maxValue) {
		return currentValue + 1;
	}

	private void testRoundTrip(Class<? extends Record> recordClass, String json, Object value) throws IOException {
		DataType type = DataType.of(recordClass);
		TypeScanner typeScanner = new TypeScanner(settings)
			.useLookup(lookup);
		TypeMap typeMap = typeScanner.scan(type).build();
		JsonValueSpec spec = typeMap.get(type);
		CodecBuilder codecBuilder = CodecBuilder.using(typeMap);
		Codec codec = codecBuilder.build();
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
		public List<Escape> values() {
			return List.of(
				new Escape("\"", "\\\""),
				new Escape("\\", "\\\\"),
				new Escape("\b", "\\b"),
				new Escape("\f", "\\f"),
				new Escape("\n", "\\n"),
				new Escape("\r", "\\r"),
				new Escape("\t", "\\t"),
				new Escape("👍", "\\ud83d\\udc4d")
			);
		}
	}

	record PresenceConditionInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(MemberPresenceCondition.class);
		}

		@Override
		public List<MemberPresenceCondition> values() {
			// No Nullary here because it's hard to make that return different
			// values for different test cases.
			var component = RecordWithOptionalField.class.getRecordComponents()[0];
			return List.of(
				memberValue(notEquals(constant(STRING, "default"))),
				enclosingObject(notEquals(constant(STRING, "default"))
					.bind(0, TypedHandles.componentAccessor(component, MethodHandles.lookup()))
				)
			);
		}
	}
}
