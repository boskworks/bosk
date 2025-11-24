package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.boson.codec.Token.FALSE;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.STRING;

/**
 * Tests cases in which {@link JsonSyntaxException} is thrown because the
 * {@link JsonReader} is unable to describe the next token,
 * as opposed to structural problems like mismatched brackets.
 */
class JsonReaderInvalidTokenTest {

	@ParameterizedTest
	@ValueSource(strings = {
		"",
		" ",
		"\n\t\r"
	})
	void emptyInput(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertEquals(Token.END_TEXT, reader.peekValueToken());
		}
	}

	@Test
	void unterminatedString() {
		try (JsonReader reader = readerFor("\"unterminated")) {
			assertEquals(STRING, reader.peekValueToken(), "Initially looks like a string");
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@Test
	void invalidEscapeSequence() {
		try (JsonReader reader = readerFor("\"invalid\\xescape\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@Test
	void incompleteUnicodeEscape() {
		try (JsonReader reader = readerFor("\"\\u12\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@Test
	void invalidUnicodeEscapeDigits() {
		try (JsonReader reader = readerFor("\"\\u12XY\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@Test
	void trailingBackslashInString() {
		try (JsonReader reader = readerFor("\"text\\\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@Test
	void unescapedControlCharacter() {
		try (JsonReader reader = readerFor("\"line\nbreak\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@Test
	void unescapedTab() {
		try (JsonReader reader = readerFor("\"has\ttab\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, reader::consumeString);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"0123",      // 0 is a valid number, but nonzero numbers can't start with 0
		"123.",      // trailing decimal
		"12.34.56",  // double decimal
		"123e",      // exponent no digits
		"123e+",     // exponent plus no digits
		"-"          // only minus
	})
	void invalidNumberWithValidFirstCharacter(String json) {
		try (JsonReader reader = readerFor(json)) {
			Token token = reader.peekValueToken();
			assertEquals(NUMBER, token);
			assertThrows(JsonSyntaxException.class, reader::consumeNumber);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		".123",
		"+123",
		"False",
	})
	void invalidFirstCharacter(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"fal",
		"fal ",
		" fal",
	})
	void partialLiteral(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertEquals(FALSE, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, ()-> {
				reader.consumeFixedToken(FALSE);
				reader.peekValueToken();
			});
		}
	}

	/**
	 * This test fails because the valid token "false" is followed
	 * by a character that does not start any valid token.
	 * <p>
	 * (Note that two tokens with no whitespace, like "falsefalse",
	 * is not a lexical error, but rather a syntax error.
	 * JSON actually has no mandatory whitespace at all.)
	 */
	@Test
	void extendedLiteral() {
		try (JsonReader reader = readerFor("falsely")) {
			assertEquals(FALSE, reader.peekValueToken());
			reader.consumeFixedToken(FALSE);
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	@Test
	void unexpectedCharacterAtTopLevel() {
		try (JsonReader reader = readerFor("@")) {
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	@Test
	void unexpectedCharacterInArray() {
		try (JsonReader reader = readerFor("[1, @]")) {
			assertEquals(START_ARRAY, reader.peekValueToken());
			reader.consumeFixedToken(Token.START_ARRAY);
			assertEquals(NUMBER, reader.peekValueToken());
			reader.consumeNumber();
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	@Test
	void singleQuoteString() {
		try (JsonReader reader = readerFor("'string'")) {
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	@Test
	void invalidUtf8Sequence() {
		byte[] invalidUtf8 = new byte[] { (byte) 0x22, (byte) 0xFF, (byte) 0xFE, (byte) 0x22 }; // "��"
		try (JsonReader reader = JsonReader.create(invalidUtf8)) {
			assertEquals(STRING, reader.peekValueToken());
			assertThrows(JsonSyntaxException.class, () -> reader.consumeString());
		}
	}

	@Test
	void singleLineComment() {
		try (JsonReader reader = readerFor("// comment\n123")) {
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	@Test
	void multiLineComment() {
		try (JsonReader reader = readerFor("/* comment */ 123")) {
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
		}
	}

	/* These are structural errors, not lexical.

	@Test
	void trailingCommaInArray() {
		try (JsonReader reader = readerFor("[1,2,]")) {
			assertEquals(START_ARRAY, reader.peekToken());
			reader.consumeFixedToken(Token.START_ARRAY);
			assertEquals(NUMBER, reader.peekToken());
			reader.consumeNumber();
			assertEquals(NUMBER, reader.peekToken());
			reader.consumeNumber();
			assertThrows(JsonLexicalException.class, reader::peekToken);
		}
	}

	@Test
	void trailingCommaInObject() {
		try (JsonReader reader = readerFor("{\"a\":1,}")) {
			assertEquals(START_OBJECT, reader.peekToken());
			reader.consumeFixedToken(Token.START_OBJECT);
			assertEquals(STRING, reader.peekToken());
			reader.consumeString();
			assertEquals(NUMBER, reader.peekToken());
			reader.consumeNumber();
			assertThrows(JsonLexicalException.class, reader::peekToken);
		}
	}

	 */

	/**
	 * Helper to create a JsonReader for a string
	 */
	private JsonReader readerFor(String json) {
		ByteArrayInputStream in = new ByteArrayInputStream(json.getBytes(UTF_8));
		return JsonReader.create(in).withValidation();
	}
}
