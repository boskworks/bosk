package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import static works.bosk.boson.codec.io.ByteChunkJsonReader.MIN_CHUNK_SIZE;

@ParameterizedClass
@MethodSource("readerSuppliers")
class JsonReaderTest {
	@Parameter
	Function<String, ? extends JsonReader> readerSupplier;

	static Stream<Function<String, ? extends JsonReader>> readerSuppliers() {
		return Stream.of(
			new ByteArray(),
			new ByteChunks()
		);
	}

	static final class ByteArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			ByteArrayInputStream in = new ByteArrayInputStream(s.getBytes(UTF_8));
			return JsonReader.create(in);
		}

		@Override
		public String toString() {
			return "Byte array";
		}
	}

	static final class ByteChunks implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return new ByteChunkJsonReader(new SynchronousChunkFiller(new ByteArrayInputStream(s.getBytes(UTF_8)), MIN_CHUNK_SIZE));
		}

		@Override
		public String toString() {
			return "Byte chunks";
		}
	}

	static final class CharArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return JsonReader.create(s.toCharArray());
		}

		@Override
		public String toString() {
			return "Char array";
		}
	}

	@Test
	void simpleString() {
		try (JsonReader reader = readerSupplier.apply("\"hello\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("hello", reader.consumeString());
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"\"😎\"",
		"\"\\uD83D\\uDE0E\""
	})
	void stringOutsideBasicMultilingualPlane(String json) {
		try (JsonReader reader = readerSupplier.apply(json)) {
			assertEquals(STRING, peekToken(reader));
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

			assertEquals(-1, reader.nextStringChar());
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@Test
	void stringWithReversedSurrogates() {
		try (JsonReader reader = readerSupplier.apply("\"\\uDE0E\\uD83D\"")) {
			assertEquals(STRING, peekToken(reader));
			reader.startConsumingString();
			assertEquals(0xde0e, reader.nextStringChar(),
				"First surrogate, even though invalid");
			assertEquals(0xd83d, reader.nextStringChar(),
				"Second surrogate");
			assertEquals(-1, reader.nextStringChar());
			assertEquals(END_TEXT, consumeToken(reader));
		}
	}

	@Test
	void stringWithEscapes() {
		try (JsonReader reader = readerSupplier.apply("\"he\\\"llo\\nworld\\\\\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("he\"llo\nworld\\", reader.consumeString());
		}
	}

	@Test
	void stringWithUnicodeEscape() {
		try (JsonReader reader = readerSupplier.apply("\"\\u0041\\u0042\\u0043\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("ABC", reader.consumeString());
		}
	}

	@Test
	void emptyString() {
		try (JsonReader reader = readerSupplier.apply("\"\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("", reader.consumeString());
		}
	}

	@Test
	void numberToken() {
		try (JsonReader reader = readerSupplier.apply("12345")) {
			assertEquals(NUMBER, peekToken(reader));
			assertEquals("12345", reader.consumeNumber().toString());
		}
	}

	@Test
	void negativeAndFractionalNumber() {
		try (JsonReader reader = readerSupplier.apply("-12.34e+5")) {
			assertEquals(NUMBER, peekToken(reader));
			assertEquals("-12.34e+5", reader.consumeNumber().toString());
		}
	}

	@Test
	void structuralTokens() {
		try (JsonReader reader = readerSupplier.apply("{\"a\": [1, 2]}")) {
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
		try (JsonReader reader = readerSupplier.apply("[true,false,null]")) {
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
		try (JsonReader reader = readerSupplier.apply("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")) {
			assertEquals(STRING, peekToken(reader));
			assertEquals("\"\\/\b\f\n\r\t", reader.consumeString());
		}
	}

	@Test
	void unterminatedStringThrows() {
		try (JsonReader reader = readerSupplier.apply("\"abc")) {
			assertEquals(STRING, peekToken(reader));
			assertThrows(IllegalStateException.class, reader::consumeString);
		}
	}

	@Test
	void invalidEscapeThrows() {
		try (JsonReader reader = readerSupplier.apply("\"abc\\x\"")) {
			assertEquals(STRING, peekToken(reader));
			assertThrows(IllegalStateException.class, reader::consumeString);
		}
	}

	private static Token peekToken(JsonReader reader) {
		return reader.peekToken();
	}

	private static Token consumeToken(JsonReader reader) {
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
