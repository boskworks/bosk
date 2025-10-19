package works.bosk.boson.codec;

import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.TestUtils.OneOfEach;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.types.DataType;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;
import static works.bosk.boson.TestUtils.ONE_OF_EACH;
import static works.bosk.boson.TestUtils.expectedOneOfEach;
import static works.bosk.boson.codec.compiler.SpecCompilerTest.testTypeMap;

@InjectFrom(SettingsInjector.class)
@TestInstance(PER_METHOD)
class CodecTest {
	final TypeMap typeMap;
	private final JsonValueSpec spec;

	CodecTest(TypeMap.Settings settings) throws NoSuchMethodException, IllegalAccessException {
		DataType type = DataType.of(OneOfEach.class);
		typeMap = testTypeMap(type, settings);
		spec = typeMap.get(type);
	}

	@InjectedTest
	void testParser() throws IOException {
		LOGGER.debug("Spec: {}", spec);
		Codec codec = CodecBuilder.using(typeMap).build();
		assertEquals(expectedOneOfEach(), codec.parserFor(spec).parse(CharArrayJsonReader.forString(ONE_OF_EACH)));
	}

	@InjectedTest
	void roundTrip() throws IOException {
		StringWriter sw = new StringWriter();

		Codec codec = CodecBuilder.using(typeMap).build();
		codec.generatorFor(spec).generate(sw, expectedOneOfEach());
		var actual = codec.parserFor(spec).parse(CharArrayJsonReader.forString(sw.toString()));
		assertEquals(expectedOneOfEach(), actual);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(CodecTest.class);
}
