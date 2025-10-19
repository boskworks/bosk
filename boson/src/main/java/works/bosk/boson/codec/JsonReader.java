package works.bosk.boson.codec;

import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.codec.io.ByteArrayChunkFiller;
import works.bosk.boson.codec.io.ByteChunkJsonReader;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.codec.io.SynchronousChunkFiller;
import works.bosk.boson.mapping.Token;

/**
 * A streaming JSON reader abstraction for high-performance parsing.
 * This interface is rather unfriendly by design.
 * Methods mutate the reader's internal state in ways that are not obvious
 * from the outside.
 * The rather stringent rules are documented here, and if you don't follow them,
 * you will get confusing behaviour.
 * The intent is that this interface allows highly tuned bytecode to describe
 * its precise requirements in a way that allows the implementation to avoid
 * all unnecessary work.
 */
public sealed interface JsonReader extends AutoCloseable permits ByteChunkJsonReader, CharArrayJsonReader {
	@Override void close(); // No throws Exception

	/**
	 * @return a new JsonReader that reads from the given stream.
	 * The stream will be closed when the reader is closed.
	 */
	static JsonReader create(InputStream stream) {
		return new ByteChunkJsonReader(new SynchronousChunkFiller(stream));
	}

	/**
	 * @return a new JsonReader that reads from the given UTF-8 byte array that contains a complete JSON document.
	 */
	static JsonReader create(byte[] utf8Bytes) {
		return new ByteChunkJsonReader(new ByteArrayChunkFiller(utf8Bytes));
	}

	/**
	 * @return a new JsonReader that reads from the given UTF-16 char array that contains a complete JSON document.
	 */
	static JsonReader create(char[] utf16Chars) {
		return new CharArrayJsonReader(utf16Chars);
	}

	/**
	 * Start by calling this.
	 * Skips insignificant characters and returns the next token encountered.
	 * <p>
	 * Depending on the token returned, the next method called must be one of the following:
	 * <ul>
	 *     <li>
	 *         for any token with a {@link Token#fixedRepresentation fixed representation},
	 *         call {@link #consumeFixedToken};
	 *     </li>
	 *     <li>
	 *         for {@link Token#NUMBER}, call {@link #consumeNumber}; or
	 *     </li>
	 *     <li>
	 *         for {@link Token#STRING}, call {@link #startConsumingString}.
	 *     </li>
	 * </ul>
	 *
	 * This method is idempotent; calling it repeatedly will return the same result.
	 */
	Token peekToken();

	default void peekToken(Token expected) {
		Token actual = peekToken();
		if (actual != expected) {
			throw new IllegalStateException("Expected " + expected + " but got " + actual);
		}
	}

	/**
	 * After {@link #peekToken} returns a token with a {@link Token#hasFixedRepresentation fixed representation},
	 * this consumes that token from the input, leaving the reader
	 * ready for the next call to {@link #peekToken}.
	 *
	 * @param token must be the last token returned by {@link #peekToken}
	 */
	void consumeFixedToken(Token token);

	default void expectFixedToken(Token expected) {
		assert expected.hasFixedRepresentation();
		peekToken(expected);
		consumeFixedToken(expected);
	}

	/**
	 * After {@link #peekToken} returns {@link Token#NUMBER NUMBER},
	 * this returns the character data
	 * comprising the text representation of the number.
	 * <p>
	 * As it happens, there exists no way to turn characters into a Java number
	 * representation without first creating a {@link CharSequence},
	 * so there's nothing to be gained by trying to avoid that allocation,
	 * nor the first-pass scan required to compute the {@link CharSequence#length() length}.
	 * We might as well do all that inside this abstraction, where we can optimize
	 * the construction of the {@link CharSequence} based on the locations
	 * of buffer boundaries and such.
	 * <p>
	 * Consumes the number from the input, leaving the reader
	 * ready for the next call to {@link #peekToken}.
	 */
	CharSequence consumeNumber();

	// String processing, used after peekToken returns STRING

	/**
	 * After {@link #peekToken} returns {@link Token#STRING STRING},
	 * this prepares to decode the string's contents.
	 * <p>
	 * After calling this, the next method called must be one of:
	 * <ul>
	 *     <li>
	 *         {@link #nextStringChar}, to decode the string one character at a time;
	 *     </li>
	 *     <li>
	 *         {@link #skipStringChars}, to skip a known number of characters;
	 *     </li>
	 *     <li>
	 *         {@link #skipToEndOfString}, to skip the rest of the string.
	 *     </li>
	 * </ul>
	 */
	void startConsumingString();

	/**
	 * Advances to the next character (code point) in the string.
	 *
	 * @return next decoded code point of the string,
	 * or -1 to indicate the end of the string,
	 * at which point the closing quote has been consumed from the input.
	 */
	int nextStringChar();

	/**
	 * Skips exactly n decoded code points, handling escape sequences.
	 * Useful for skipping known portions of a string.
	 * <p>
	 * Note that this never consumes the closing quote.
	 * One of {@link #nextStringChar} or {@link #skipToEndOfString}
	 * must be called to finish consuming the string.
	 *
	 * @param n number of characters to skip
	 * @throws IllegalArgumentException if {@code n} is negative
	 * @throws IllegalStateException if {@code n} is more than the remaining characters in the string
	 */
	void skipStringChars(int n);

	/**
	 * Skips the remainder of the string token, as though {@link #nextStringChar()}
	 * had been called repeatedly until it returned -1.
	 * Useful if the rest of the string's value isn't needed.
	 */
	void skipToEndOfString();

	/**
	 * A variant of {@link #skipToEndOfString} that asserts that the number of remaining
	 * characters in the string is as expected.
	 * Can be faster than {@link #skipToEndOfString} if the number of remaining characters is known.
	 * <p>
	 * Equivalent to calling {@link #skipStringChars(int)} with the given number,
	 * followed by {@link #nextStringChar()} to consume the closing quote,
	 * and (optionally) verifying that -1 is returned.
	 * If {@code remainingChars} is incorrect, the wrong number of characters may be skipped.
	 * <p>
	 * Note that simply calling {@link #skipToEndOfString()} is also a valid implementation.
	 *
	 * @param remainingChars the expected number of remaining characters in the string
	 * @see #skipToEndOfString()
	 */
	default void skipToEndOfString(int remainingChars) {
		skipToEndOfString();
	}

	/**
	 * A variant of {@link #startConsumingString} that adds the entire string's contents
	 * to a given {@link StringBuilder}.
	 * Handy if you need the entire string.
	 * <p>
	 * Consumes the string input, leaving the reader
	 * ready for the next call to {@link #peekToken}.
	 *
	 * @see #nextStringChar()
	 */
	default void consumeStringContents(StringBuilder sb) {
		startConsumingString();
		int c;
		while ((c = nextStringChar()) != -1) {
			sb.appendCodePoint(c);
		}
	}

	default String consumeString() {
		StringBuilder sb = new StringBuilder();
		consumeStringContents(sb);
		return sb.toString();
	}

	// Diagnostics etc

	/**
	 * On a best-effort basis, return the upcoming characters in the input.
	 */
	String previewString(int requestedLength);

	/**
	 * TODO: Tighten this up
	 * @return some notion of the current offset in the input. Useful for diagnostics.
	 */
	long currentOffset();

	Logger LOGGER = LoggerFactory.getLogger(JsonReader.class);
}
