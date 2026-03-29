package works.bosk.boson.codec.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.exceptions.JsonFormatException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.boson.codec.Token.END_TEXT;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.CARRYOVER_BYTES;

/**
 * Tests the {@link JsonReader#validateSyntax(CharSequence)} method.
 * The input we're testing here isn't even valid JSON;
 * we're just using the reader's ability to validate specified character sequences.
 */
@InjectFields
@InjectFrom(JsonReaderValidateSyntaxInjector.class)
public class JsonReaderValidateSyntaxTest {
	@Injected JsonReaderValidateSyntaxInjector.ReaderFactoryParameter parameter;

	private JsonReader reader;

	@BeforeEach
	void setup() {
		String text = "1234567890".repeat(4).substring(0, 22);
		reader = parameter.factory().create(text, 11);

		assertEquals(5, CARRYOVER_BYTES);

		reader.validateSyntax("1234");
		reader.validateSyntax("");
		reader.validateSyntax("56");
		reader.validateSyntax("7890");
		reader.validateSyntax("1");
	}

	@Test
	void wrongImmediately() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateSyntax("x"));
	}

	@Test
	void wrongAfterRight() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateSyntax("234x"));
	}

	@Test
	void wrongAfterBoundary() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateSyntax("2345x"));
	}

	@Test
	void wrongOnLastCharacter() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateSyntax("2345678901x"));
	}

	@Test
	void rightToTheEnd() {
		reader.validateSyntax("23456789012");
		reader.validateSyntax("");
		assertEquals(END_TEXT, reader.peekValueToken());
	}

	@Test
	void rightButTooLong() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateSyntax("23456789012x"));
	}
}
