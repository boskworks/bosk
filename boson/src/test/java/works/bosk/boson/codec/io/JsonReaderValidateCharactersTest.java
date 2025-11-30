package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.exceptions.JsonFormatException;
import works.bosk.junit.InjectedTest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.boson.codec.Token.END_TEXT;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.CARRYOVER_BYTES;

/**
 * Tests the {@link JsonReader#validateCharacters(CharSequence)} method.
 * The input we're testing here isn't even valid JSON;
 * we're just using the reader's ability to validate specified character sequences.
 */
@ParameterizedClass
@MethodSource("readers")
public class JsonReaderValidateCharactersTest {
	private final JsonReader reader;

	JsonReaderValidateCharactersTest(ReaderFactoryParameter p) {
		// We do the happy parts that are expected to pass in here.
		// This doubles as setup for later tests that
		// expect failures, because those ruin the reader.

		String text = "1234567890".repeat(4).substring(0, 22);
		reader = p.factory().create(text, 11);

		// self-check. The tests are written to assume this
		assertEquals(5, CARRYOVER_BYTES);

		// The number of characters per chunk will be 11-CARRYOVER_BYTES = 5

		// Stop before chunk boundary
		reader.validateCharacters("1234");
		// Zero-sized string always matches
		reader.validateCharacters("");
		// Cross chunk boundary
		reader.validateCharacters("56");
		// Stop on chunk boundary
		reader.validateCharacters("7890");
		// Make sure we're still good
		reader.validateCharacters("1");

		// The reader is left with 2345, 67890, and 12 in three chunks
	}

	public static List<ReaderFactoryParameter> readers() {
		return List.of(
			new ReaderFactoryParameter("overlapped", (json, chunkSize) -> {
				var filler = new OverlappedPrefetchingChunkFiller(
					new ByteArrayInputStream(json.getBytes(UTF_8)),
					chunkSize, 2
				);
				return new ByteChunkJsonReader(filler);
			}),
			new ReaderFactoryParameter("synchronous", (json, chunkSize) -> {
				var filler = new SynchronousChunkFiller(
					new ByteArrayInputStream(json.getBytes(UTF_8)),
					chunkSize
				);
				return new ByteChunkJsonReader(filler);
			}),
			new ReaderFactoryParameter("char array", (json, _) -> {
				char[] chars = json.toCharArray();
				return new CharArrayJsonReader(chars);
			})
		);
	}

	@InjectedTest
	void wrongImmediately() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateCharacters("x"));
	}

	@InjectedTest
	void wrongAfterRight() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateCharacters("234x"));
	}

	@InjectedTest
	void wrongAfterBoundary() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateCharacters("2345x"));
	}

	@InjectedTest
	void wrongOnLastCharacter() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateCharacters("2345678901x"));
	}

	@InjectedTest
	void rightToTheEnd() {
		reader.validateCharacters("23456789012");
		// Empty string matches even at the end
		reader.validateCharacters("");
		assertEquals(END_TEXT, reader.peekValueToken());
	}

	@InjectedTest
	void rightButTooLong() {
		assertThrows(JsonFormatException.class,
			() -> reader.validateCharacters("23456789012x"));
	}

	interface ReaderFactory {
		JsonReader create(String json, int chunkSize);
	}

	/**
	 * Gives meaningful names to reader factories so it's easier
	 * to understand the test reports.
	 */
	public record ReaderFactoryParameter(String name, ReaderFactory factory) {
		@Override
		public String toString() {
			return name;
		}
	}

}
