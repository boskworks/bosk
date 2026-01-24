package works.bosk.boson.codec.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.codec.Token.COLON;
import static works.bosk.boson.codec.Token.COMMA;
import static works.bosk.boson.codec.Token.END_ARRAY;
import static works.bosk.boson.codec.Token.END_OBJECT;
import static works.bosk.boson.codec.Token.END_TEXT;
import static works.bosk.boson.codec.Token.FALSE;
import static works.bosk.boson.codec.Token.NULL;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.START_OBJECT;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.Token.TRUE;
import static works.bosk.boson.codec.Token.WHITESPACE;

@ParameterizedClass
@MethodSource("readerSuppliers")
class JsonReaderHappyTest extends AbstractJsonReaderTest {

	@Test
	void simpleString() {
		try (JsonReader reader = readerFor("\"hello\"")) {
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("hello", reader.consumeString());
			assertEquals(END_TEXT, consumeValueToken(reader));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"\"😎\"",
		"\"\\uD83D\\uDE0E\"",
		"\"😎\" ", // space after closing quote
	})
	void stringOutsideBasicMultilingualPlane(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertEquals(STRING, peekValueToken(reader));
			reader.startConsumingString();

			// Two possibilities are valid here: the surrogate pair, or the full code point.
			int firstChar = reader.nextStringChar();
			if (Character.isBmpCodePoint(firstChar)) {
				assertEquals(0xd83d, firstChar,
					"High surrogate");
				assertEquals(0xde0e, reader.nextStringChar(),
					"Low surrogate");
			} else {
				assertEquals(0x1f60e, firstChar,
					"Entire code point");
			}

			assertEquals(-2, reader.nextStringChar());
			assertEquals(END_TEXT, consumeValueToken(reader));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"\"A😎Z\"",
		"\"A\\uD83D\\uDE0EZ\"",
	})
	void skipStringOutsideBMP(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertEquals(STRING, peekValueToken(reader));
			reader.startConsumingString();
			reader.skipStringChars(3);
			assertEquals(-2, reader.nextStringChar());
			assertEquals(END_TEXT, consumeValueToken(reader));
		}
	}

	@Test
	void stringWithReversedSurrogates() {
		try (JsonReader reader = readerFor("\"\\uDE0E\\uD83D\"")) {
			assertEquals(STRING, peekValueToken(reader));
			reader.startConsumingString();
			assertEquals(0xde0e, reader.nextStringChar(),
				"First surrogate, even though invalid");
			assertEquals(0xd83d, reader.nextStringChar(),
				"Second surrogate");
			assertEquals(-2, reader.nextStringChar());
			assertEquals(END_TEXT, consumeValueToken(reader));
		}
	}

	@Test
	void stringWithSurrogates() {
		try (JsonReader reader = readerFor("\"\uD83D\uDE0E\"")) {
			assertEquals(STRING, peekValueToken(reader));
			String string = reader.consumeString();
			assertEquals("\uD83D\uDE0E", string);
			assertEquals("😎", string);
		}
	}

	@Test
	void stringWithEscapes() {
		try (JsonReader reader = readerFor("\"he\\\"llo\\nworld\\\\\"")) {
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("he\"llo\nworld\\", reader.consumeString());
		}
	}

	@Test
	void stringWithUnicodeEscape() {
		try (JsonReader reader = readerFor("\"\\u0041\\u0042\\u0043\"")) {
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("ABC", reader.consumeString());
		}
	}

	/**
	 * JSON has no rules requiring surrogates to be correctly paired.
	 */
	@Test
	void stringWithBackwardSurrogatePair() {
		try (JsonReader reader = readerFor("\"\\uDC00\\uD800\"")) { // Low surrogate before high surrogate
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("\uDC00\uD800", reader.consumeString());
		}
	}

	@Test
	void stringWithHighSurrogateOnly() {
		try (JsonReader reader = readerFor("\"\\uD800\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertEquals("\uD800", reader.consumeString());
		}
	}

	@Test
	void stringWithLowSurrogateOnly() {
		try (JsonReader reader = readerFor("\"\\uDC00\"")) {
			assertEquals(STRING, reader.peekValueToken());
			assertEquals("\uDC00", reader.consumeString());
		}
	}


	@Test
	void emptyString() {
		try (JsonReader reader = readerFor("\"\"")) {
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("", reader.consumeString());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"12345",
		" 12345",
		"12345 ",
	})
	void numberToken(String json) {
		try (JsonReader reader = readerFor(json)) {
			assertEquals(NUMBER, peekValueToken(reader));
			assertEquals("12345", reader.consumeNumber().toString());
		}
	}

	@Test
	void negativeAndFractionalNumber() {
		try (JsonReader reader = readerFor("-12.34e+5")) {
			assertEquals(NUMBER, peekValueToken(reader));
			assertEquals("-12.34e+5", reader.consumeNumber().toString());
		}
	}

	@Test
	void structuralTokens() {
		try (JsonReader reader = readerFor("{\"a\": [1, 2]}")) {
			assertEquals(START_OBJECT, consumeValueToken(reader));
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("a", reader.consumeString());
			assertEquals(COLON, reader.peekRawToken());
			assertEquals(START_ARRAY, consumeValueToken(reader));
			assertEquals(NUMBER, peekValueToken(reader));
			assertEquals("1", reader.consumeNumber().toString());
			assertEquals(NUMBER, peekValueToken(reader));
			assertEquals("2", reader.consumeNumber().toString());
			assertEquals(END_ARRAY, consumeValueToken(reader));
			assertEquals(END_OBJECT, consumeValueToken(reader));
			assertEquals(END_TEXT, consumeValueToken(reader));
		}
	}

	@Test
	void trueFalseNull() {
		try (JsonReader reader = readerFor("[true, false,null]")) {
			assertEquals(START_ARRAY, consumeValueToken(reader));
			assertEquals(TRUE, consumeValueToken(reader));
			assertEquals(COMMA, reader.peekRawToken());
			reader.consumeSyntax(COMMA);
			assertEquals(WHITESPACE, reader.peekRawToken());
			assertEquals(FALSE, consumeValueToken(reader));
			assertEquals(NULL, consumeValueToken(reader));
			assertEquals(END_ARRAY, consumeValueToken(reader));
			assertEquals(END_TEXT, consumeValueToken(reader));
		}
	}

	@Test
	void stringWithAllEscapes() {
		try (JsonReader reader = readerFor("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")) {
			assertEquals(STRING, peekValueToken(reader));
			assertEquals("\"\\/\b\f\n\r\t", reader.consumeString());
		}
	}

	@Test
	void peekNonWhitespaceToken() {
		try (JsonReader reader = readerFor("  \n { \t \"key\" \r : \t [ \r 123 , 456 \n ] }  ")) {
			assertEquals(START_OBJECT, consumeNonWhitespaceToken(reader));
			assertEquals(STRING, consumeNonWhitespaceToken(reader));
			assertEquals(COLON, consumeNonWhitespaceToken(reader));
			assertEquals(START_ARRAY, consumeNonWhitespaceToken(reader));
			assertEquals(NUMBER, consumeNonWhitespaceToken(reader));
			assertEquals(COMMA, consumeNonWhitespaceToken(reader));
			assertEquals(NUMBER, consumeNonWhitespaceToken(reader));
			assertEquals(END_ARRAY, consumeNonWhitespaceToken(reader));
			assertEquals(END_OBJECT, consumeNonWhitespaceToken(reader));
			assertEquals(END_TEXT, consumeNonWhitespaceToken(reader));
			assertEquals(END_TEXT, consumeNonWhitespaceToken(reader),
				"Unlimited END_TEXT tokens at end of input is valid");
		}
	}

	private static Token peekValueToken(JsonReader reader) {
		return reader.peekValueToken();
	}

}
