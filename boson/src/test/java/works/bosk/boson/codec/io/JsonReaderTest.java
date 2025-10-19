package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.mapping.Token;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.boson.mapping.Token.END_ARRAY;
import static works.bosk.boson.mapping.Token.END_OBJECT;
import static works.bosk.boson.mapping.Token.END_TEXT;
import static works.bosk.boson.mapping.Token.FALSE;
import static works.bosk.boson.mapping.Token.NULL;
import static works.bosk.boson.mapping.Token.NUMBER;
import static works.bosk.boson.mapping.Token.START_ARRAY;
import static works.bosk.boson.mapping.Token.START_OBJECT;
import static works.bosk.boson.mapping.Token.STRING;
import static works.bosk.boson.mapping.Token.TRUE;

class JsonReaderTest {

	@Test
	void simpleString() {
		try (JsonReader reader = readerFor("\"hello\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("hello", reader.consumeString());
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@Test
	void stringWithUnicode() {
		try (JsonReader reader = readerFor("\"hello 😎\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("hello 😎", reader.consumeString());
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@Test
	void stringWithEscapes() {
		try (JsonReader reader = readerFor("\"he\\\"llo\\nworld\\\\\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("he\"llo\nworld\\", reader.consumeString());
		}
	}

	@Test
	void stringWithUnicodeEscape() {
		try (JsonReader reader = readerFor("\"\\u0041\\u0042\\u0043\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("ABC", reader.consumeString());
		}
	}

	@Test
	void emptyString() {
		try (JsonReader reader = readerFor("\"\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("", reader.consumeString());
		}
	}

	@Test
	void numberToken() {
		try (JsonReader reader = readerFor("12345")) {
			assertEquals(NUMBER, peekToken(reader));
			assertEquals("12345", reader.consumeNumber().toString());
		}
	}

	@Test
	void negativeAndFractionalNumber() {
		try (JsonReader reader = readerFor("-12.34e+5")) {
			assertEquals(NUMBER, peekToken(reader));
			assertEquals("-12.34e+5", reader.consumeNumber().toString());
		}
	}

	@Test
	void structuralTokens() {
		try (JsonReader reader = readerFor("{\"a\": [1, 2]}")) {
			assertEquals(START_OBJECT, consumeToken(reader));
			assertEquals(STRING, peekToken(reader));
			assertEquals("a", reader.consumeString());
			assertEquals(START_ARRAY, consumeToken(reader));
			assertEquals(NUMBER, peekToken(reader));
			assertEquals("1", reader.consumeNumber().toString());
			assertEquals(NUMBER, peekToken(reader));
			assertEquals("2", reader.consumeNumber().toString());
			assertEquals(END_ARRAY, consumeToken(reader));
			assertEquals(END_OBJECT, consumeToken(reader));
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@Test
	void trueFalseNull() {
		try (JsonReader reader = readerFor("[true,false,null]")) {
			assertEquals(START_ARRAY, consumeToken(reader));
			assertEquals(TRUE, consumeToken(reader));
			assertEquals(FALSE, consumeToken(reader));
			assertEquals(NULL, consumeToken(reader));
			assertEquals(END_ARRAY, consumeToken(reader));
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@Test
	void stringWithAllEscapes() {
		try (JsonReader reader = readerFor("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("\"\\/\b\f\n\r\t", reader.consumeString());
		}
	}

	@Test
	void unterminatedStringThrows() {
		try (JsonReader reader = readerFor("\"abc")) {
			assertEquals(STRING, peekToken(reader));
			assertThrows(IllegalStateException.class, reader::consumeString);
		}
	}

	@Test
	void invalidEscapeThrows() {
		try (JsonReader reader = readerFor("\"abc\\x\"")) {
			assertEquals(STRING, peekToken(reader));
			assertThrows(IllegalStateException.class, reader::consumeString);
		}
	}

	/**
	 * Helper to create a JsonReader for a string
	 */
	private JsonReader readerFor(String json) {
		ByteArrayInputStream in = new ByteArrayInputStream(json.getBytes(UTF_8));
		return JsonReader.create(in);
	}

	private Token peekToken(JsonReader reader) {
		return reader.peekToken();
	}

	private Token consumeToken(JsonReader reader) {
		Token token = reader.peekToken();
		if (token.hasFixedRepresentation()) {
			reader.consumeFixedToken(token);
		} else if (token == STRING) {
			reader.skipToEndOfString();
		} else if (token == NUMBER) {
			reader.consumeNumber();
		}
		return token;
	}
}
