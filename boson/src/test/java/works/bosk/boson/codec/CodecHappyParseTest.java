package works.bosk.boson.codec;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.TestInstance;
import works.bosk.boson.codec.RoundTripTest.Primitive;
import works.bosk.boson.mapping.TypeMap.Settings;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;
import static works.bosk.boson.types.DataType.STRING;

/**
 * Tests that {@link Codec} parses valid JSON correctly.
 */
@InjectFrom({SettingsInjector.class, RoundTripTest.PrimitiveInjector.class})
@TestInstance(PER_METHOD)
public class CodecHappyParseTest {
	final Settings settings;
	final TypeScanner scanner;

	public CodecHappyParseTest(Settings settings) {
		this.settings = settings;
		scanner = new TypeScanner(settings).use(MethodHandles.lookup());
	}

	@InjectedTest
	void bareLiterals() throws IOException {
		var typeMap = scanner
			.scan(DataType.BOOLEAN)
			.scan(STRING)
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		assertEquals(false,
			codec.parserFor(new BooleanNode()).parse(JsonReader.create("false")));
		assertEquals(true,
			codec.parserFor(new BooleanNode()).parse(JsonReader.create("true")));
		assertNull(codec.parserFor(new MaybeNullSpec(new StringNode())).parse(JsonReader.create("null")));
	}

	@InjectedTest
	void primitiveNumber(Primitive numberCase) throws IOException {
		var typeMap = scanner
			.scan(DataType.of(numberCase.type()))
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		assertEquals(numberCase.value(),
			codec
				.parserFor(new PrimitiveNumberNode(numberCase.type()))
				.parse(JsonReader.create(numberCase.json())));
	}

	@InjectedTest
	void boxedNumber(Primitive numberCase) throws IOException {
		var typeMap = scanner
			.scan(DataType.of(numberCase.boxedType()))
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		BoxedPrimitiveSpec spec = new BoxedPrimitiveSpec(new PrimitiveNumberNode(numberCase.type()));
		assertEquals(numberCase.value(),
			codec.parserFor(spec)
				.parse(JsonReader.create(numberCase.json())));
		assertNull(codec.parserFor(new MaybeNullSpec(spec)).parse(JsonReader.create("null")));
	}

	@InjectedTest
	void bigDecimal() throws IOException {
		var typeMap = scanner
			.scan(DataType.of(BigDecimal.class))
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		var pi = "3.141592653589793238462643383279502884197169399375105820974944";
		assertEquals(new BigDecimal(pi),
			codec.parserFor(new BigNumberNode(BigDecimal.class)).parse(JsonReader.create(pi)));
	}

	@InjectedTest
	void enumByName() throws IOException {
		var typeMap = scanner
			.scan(DataType.of(TimeUnit.class))
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		assertEquals(TimeUnit.DAYS,
			codec.parserFor(new EnumByNameNode(TimeUnit.class)).parse(JsonReader.create("\"DAYS\"")));
	}

	@InjectedTest
	void fixedMap() throws IOException {
		record TestRecord(int intField, String strField) {}
		var typeMap = scanner
			.scan(DataType.of(TestRecord.class))
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		var json = """
			{
				"intField": 123,
				"strField": "Hello, World!"
			}
			""";
		assertEquals(new TestRecord(123, "Hello, World!"),
			codec.parserFor(typeMap.get(DataType.of(TestRecord.class)))
				.parse(JsonReader.create(json)));
	}

	@InjectedTest
	void fixedMapWithOptionalField() throws IOException {
		record TestRecord(int intField, String strField) {}
		var mandatory = FixedMapMember.forComponent(TestRecord.class.getRecordComponents()[1], MethodHandles.lookup());
		var optional = new FixedMapMember(
			new MaybeAbsentSpec(
				new StringNode(),
				new ComputedSpec(TypedHandle.ofSupplier(STRING, () -> "TEST_DEFAULT")),
				MemberPresenceCondition.always()
			),
			mandatory.accessor()
		);
		var typeMap = scanner
			.specifyRecordFields(TestRecord.class, Map.of("strField", optional))
			.scan(DataType.of(TestRecord.class))
			.build();
		var codec = CodecBuilder.using(typeMap).build();

		assertEquals(new TestRecord(123, "Hello, World!"),
			codec.parserFor(typeMap.get(DataType.of(TestRecord.class)))
				.parse(JsonReader.create("""
					{
						"intField": 123,
						"strField": "Hello, World!"
					}
					""")));
		assertEquals(new TestRecord(456, "TEST_DEFAULT"),
			codec.parserFor(typeMap.get(DataType.of(TestRecord.class)))
				.parse(JsonReader.create("""
					{
						"intField": 456
					}
					""")));
	}
}
