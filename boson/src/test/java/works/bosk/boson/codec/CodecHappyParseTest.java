package works.bosk.boson.codec;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import org.junit.jupiter.api.TestInstance;
import works.bosk.boson.mapping.TypeMap.Settings;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.types.DataType;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

/**
 * Tests that {@link Codec} parses valid JSON correctly.
 */
@InjectFrom(SettingsInjector.class)
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
			.scan(DataType.STRING)
			.build();
		var codec = CodecBuilder.using(typeMap).build();
		assertEquals(false,
			codec.parserFor(new BooleanNode()).parse(JsonReader.create("false")));
		assertEquals(true,
			codec.parserFor(new BooleanNode()).parse(JsonReader.create("true")));
		assertNull(codec.parserFor(new MaybeNullSpec(new StringNode())).parse(JsonReader.create("null")));
	}
}
