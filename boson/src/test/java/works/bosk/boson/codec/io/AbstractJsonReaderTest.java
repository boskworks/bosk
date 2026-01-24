package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.Parameter;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;

import static java.nio.charset.StandardCharsets.UTF_8;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.Token.WHITESPACE;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.MIN_CHUNK_SIZE;

public class AbstractJsonReaderTest {
	@Parameter
	@SuppressWarnings("unused") // Initialized by JUnit
	private Function<String, ? extends JsonReader> readerSupplier;

	static Token consumeNonWhitespaceToken(JsonReader reader) {
		Token token = reader.peekNonWhitespaceToken();
		assert token != WHITESPACE;
		if (token.isInsignificant()) {
			reader.consumeSyntax(token);
			return token;
		} else {
			return consumeValueToken(reader);
		}
	}

	static Token consumeValueToken(JsonReader reader) {
		Token token = reader.peekValueToken();
		if (token.hasFixedRepresentation()) {
			reader.consumeSyntax(token);
		} else if (token == STRING) {
			reader.startConsumingString();
			reader.skipToEndOfString();
		} else if (token == NUMBER) {
			reader.consumeNumber();
		}
		return token;
	}

	protected JsonReader readerFor(String json) {
		return readerSupplier.apply(json);
	}

	@SuppressWarnings("unused") // Subclasses use this to parameterize tests
	static Stream<Function<String, JsonReader>> readerSuppliers() {
		return Stream.of(
			new ByteArray(),
			new ByteChunks(),
			new CharArray(),
			new CharArray() {
				@Override
				public JsonReader apply(String s) {
					return super.apply(s).withValidation();
				}

				@Override
				public String toString() {
					return "Validating " + super.toString();
				}
			}
		);
	}

	static class ByteArray implements Function<String, JsonReader> {
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

	static class ByteChunks implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return new ByteChunkJsonReader(new SynchronousChunkFiller(new ByteArrayInputStream(s.getBytes(UTF_8)), MIN_CHUNK_SIZE));
		}

		@Override
		public String toString() {
			return "Byte chunks";
		}
	}

	static class CharArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return JsonReader.create(s.toCharArray());
		}

		@Override
		public String toString() {
			return "Char array";
		}
	}
}
