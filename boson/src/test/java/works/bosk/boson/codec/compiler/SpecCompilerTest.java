package works.bosk.boson.codec.compiler;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import works.bosk.boson.TestUtils.Month;
import works.bosk.boson.TestUtils.OneOfEach;
import works.bosk.boson.codec.Parser;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.RecognizedMember;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.TestUtils.ABSENT_FIELD_VALUE;
import static works.bosk.boson.TestUtils.COMPUTED_FIELD_VALUE;
import static works.bosk.boson.TestUtils.ONE_OF_EACH;
import static works.bosk.boson.TestUtils.expectedOneOfEach;
import static works.bosk.boson.TestUtils.testCallback;
import static works.bosk.boson.mapping.TypeMap.Settings.DEFAULT;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.memberValue;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.constant;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.notEquals;
import static works.bosk.boson.types.DataType.BOOLEAN;
import static works.bosk.boson.types.DataType.INT;
import static works.bosk.boson.types.DataType.STRING;

public class SpecCompilerTest {

	static final Map<Package, MethodHandles.Lookup> LOOKUP_MAP = Map.of(SpecCompilerTest.class.getPackage(), MethodHandles.lookup());

	@Test
	void testBoolean() throws IOException, NoSuchMethodException, IllegalAccessException {
		Parser parser = compiledParser(BOOLEAN);
		Boolean actual = (Boolean) parser.parse(CharArrayJsonReader.forString("true"));
		assertEquals(Boolean.TRUE, actual);
	}

	@Test
	void testString() throws IOException, NoSuchMethodException, IllegalAccessException {
		Parser parser = compiledParser(STRING);
		String actual = (String) parser.parse(CharArrayJsonReader.forString("\"testing\""));
		assertEquals("testing", actual);
	}

	@Test
	void testInt() throws IOException, NoSuchMethodException, IllegalAccessException {
		Parser parser = compiledParser(INT);
		int actual = (int) parser.parse(CharArrayJsonReader.forString("123"));
		assertEquals(123, actual);
	}

	@Test
	void testBigDecimal() throws IOException, NoSuchMethodException, IllegalAccessException {
		Parser parser = compiledParser(DataType.of(BigDecimal.class));
		BigDecimal actual = (BigDecimal) parser.parse(CharArrayJsonReader.forString("123.456"));
		assertEquals(new BigDecimal("123.456"), actual);
	}

	@Test
	void testEnum() throws IOException, NoSuchMethodException, IllegalAccessException {
		enum TestEnum { TEST1, TEST2 }
		Parser parser = compiledParser(DataType.of(TestEnum.class));
		TestEnum actual = (TestEnum) parser.parse(CharArrayJsonReader.forString("\"TEST1\""));
		assertEquals(TestEnum.TEST1, actual);
	}

	@Test
	void testTypeRef() throws IOException {
		enum TestEnum { TEST1, TEST2 }
		KnownType type = DataType.known(TestEnum.class);
		TypeRefNode typeRefNode = new TypeRefNode(type);
		TypeMap typeMap = new TypeScanner(DEFAULT)
			.useLookup(MethodHandles.lookup())
			.scan(type)
			.build();

		// Compiling a reference node should work exactly the same as
		// whatever node the typeScanner would return for TestEnum.
		Parser parser = new SpecCompiler(typeMap).compile().parserFor(typeRefNode);
		TestEnum actual = (TestEnum) parser.parse(CharArrayJsonReader.forString("\"TEST1\""));
		assertEquals(TestEnum.TEST1, actual);
	}

	public record OuterRecord(int i1, InnerRecord inner){}
	public record InnerRecord(int i2){}

	@Test
	void testRecord() throws IOException, NoSuchMethodException, IllegalAccessException {
		Parser parser = compiledParser(DataType.of(OuterRecord.class));
		String json = """
				{
					"i1": 123,
					"inner": { "i2": 456 }
				}
			""";
		OuterRecord actual = (OuterRecord) parser.parse(CharArrayJsonReader.forString(json));
		OuterRecord expected = new ObjectMapper().readerFor(OuterRecord.class).readValue(json);
		assertEquals(expected, actual);
	}

	@Test
	void testOneOfEach() throws IOException, NoSuchMethodException, IllegalAccessException {
		Parser parser = compiledParser(DataType.of(OneOfEach.class));
		OneOfEach actual = (OneOfEach) parser.parse(CharArrayJsonReader.forString(ONE_OF_EACH));
		assertEquals(expectedOneOfEach(), actual);
	}

	private Parser compiledParser(DataType dataType) throws NoSuchMethodException, IllegalAccessException {
		var typeMap = testTypeMap(dataType, new TypeMap.Settings(true, true, true, true, false));
		return new SpecCompiler(typeMap).compile().parserFor(typeMap.get(dataType));
	}

	public static TypeMap testTypeMap(DataType dataType, TypeMap.Settings settings) throws NoSuchMethodException, IllegalAccessException {
		TypedHandle computedFieldValue = constant(STRING, COMPUTED_FIELD_VALUE);
		TypedHandle absentFieldValue = constant(STRING, ABSENT_FIELD_VALUE);
		TypedHandle getMaybeAbsentField = new TypedHandle(
			MethodHandles.lookup().unreflect(OneOfEach.class.getMethod("maybeAbsentField")),
			DataType.known(String.class),
			List.of(DataType.known(OneOfEach.class))
		);
		MemberPresenceCondition isPresent = memberValue(notEquals(constant(STRING, ABSENT_FIELD_VALUE)));
		return new TypeScanner(settings)
			.useLookup(MethodHandles.lookup())
			.specify(DataType.known(Month.class), Month.specNode())
			.specifyRecordFields(OneOfEach.class, Map.of(
				"computedField", new RecognizedMember(new ComputedSpec(computedFieldValue), computedFieldValue),
				"maybeAbsentField", new RecognizedMember(new MaybeAbsentSpec(
					testCallback(new StringNode()), new ComputedSpec(absentFieldValue), isPresent),
					getMaybeAbsentField
				)
			))
			.scan(dataType)
			.build();
	}

}
