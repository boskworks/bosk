package works.bosk.boson.codec.io;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.boson.codec.Token.FALSE;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.Token.WHITESPACE;

/**
 * Tests cases in which {@link JsonSyntaxException} is thrown because the
 * {@link JsonReader} is unable to describe the next token,
 * as opposed to structural problems like mismatched brackets.
 */
@ParameterizedClass
@MethodSource("readerSuppliers")
class JsonReaderInvalidTokenTest extends AbstractJsonReaderTest {

	@Override
	protected JsonReader readerFor(String json) {
		// We need validation on all readers here
		return super.readerFor(json).withValidation();
	}

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

	@ParameterizedTest
	@ValueSource(strings = {
		"\f  ",
	})
	void invalidWhitespaceImmediately(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertThrows(JsonSyntaxException.class, reader::peekRawToken);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		" \f",
	})
	void invalidWhitespaceLater(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertEquals(WHITESPACE, reader.peekRawToken());
			assertThrows(JsonSyntaxException.class, reader::peekValueToken);
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
			assertThrows(JsonSyntaxException.class, reader::consumeString);
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

	@Test
	void justAQuote() {
		try (JsonReader reader = readerFor("\"")) {
			assertEquals(STRING, reader.peekValueToken());
			reader.startConsumingString();
			assertThrows(JsonSyntaxException.class, reader::nextStringChar);
		}
	}

	@Test
	void trailingBackslash() {
		// Opening quote then backslash, end of input
		try (JsonReader reader = readerFor(new String(new char[] { '"', '\\' }))) {
			assertEquals(STRING, reader.peekValueToken());
			reader.startConsumingString();
			assertThrows(JsonSyntaxException.class, reader::nextStringChar);
		}
	}

	/**
	 * Java has difficulty representing unpaired surrogates in string literals.
	 * The UTF-8 based readers can't represent this case at all.
	 * This case is probably not really worth testing anyway though.
	 */
	@Disabled
	@Test
	void trailingHalfSurrogate() {
		// Opening quote then an unpaired high surrogate
		String json = new String(new char[]{'"', '\uD800'});
		try (JsonReader reader = readerFor(json)) {
			assertEquals(STRING, reader.peekValueToken());
			reader.startConsumingString();
			assertEquals('\uD800', reader.nextStringChar());
			assertThrows(JsonSyntaxException.class, reader::nextStringChar);
		}
	}

	@Test
	void trailingPartialUnicodeEscape() {
		// Half a unicode escape sequence
		try (JsonReader reader = readerFor(new String(new char[] { '"', '\\', 'u', '1', '2' }))) {
			assertEquals(STRING, reader.peekValueToken());
			reader.startConsumingString();
			assertThrows(JsonSyntaxException.class, reader::nextStringChar);
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

}
