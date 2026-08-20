package works.bosk.boson.codec.io;

import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static java.lang.Character.MAX_SURROGATE;
import static java.lang.Character.MIN_SURROGATE;
import static java.lang.Math.min;

/**
 * A {@link JsonReader} that reads from a char array.
 * Useful for reading JSON text that is small enough to have been
 * fully loaded into memory already, like when reading from a String.
 * <p>
 * Does only as much JSON validation as can be done with no performance impact.
 */
public final class CharArrayJsonReader implements JsonReader {
	final char[] chars;
	int pos = 0;

	public CharArrayJsonReader(char[] chars) {
		this.chars = chars;
	}

	public static CharArrayJsonReader forString(String s) {
		return new CharArrayJsonReader(s.toCharArray());
	}

	@Override
	public Token peekValueToken() {
		skipInsignificant();
		return peekRawToken();
	}

	@Override
	public Token peekNonWhitespaceToken() {
		skipWhitespace();
		return peekRawToken();
	}

	/**
	 * @return NOT a code point!
	 */
	private int peekRawChar() {
		if (pos >= chars.length) {
			return -1;
		} else {
			return chars[pos];
		}
	}

	private void skipInsignificant() {
		while (Util.fast_isInsignificant(peekRawChar())) {
			pos++;
		}
	}

	private void skipWhitespace() {
		while (Util.fast_isWhitespace(peekRawChar())) {
			pos++;
		}
	}

	@Override
	public void consumeSyntax(Token token) {
		// assert peekRawToken() == token;
		pos += token.fixedRepresentation().length();
	}

	@Override
	public CharSequence consumeNumber() {
		int start = pos;
		while (pos < chars.length && Util.isNumberChar(chars[pos])) {
			pos++;
		}
		return new CharArraySequence(start, pos);
	}

	@Override
	public void startConsumingString() {
		// assert peekRawToken() == Token.STRING;
		pos++; // Skip opening quote
	}

	@Override
	public int nextStringChar() {
		if (pos >= chars.length) {
			throw new JsonSyntaxException("Unterminated string at end of input");
		}
		char c = chars[pos++];
		if (c == '"') {
			return END_OF_STRING;
		} else if (c == '\\') {
			return decodeEscape();
		} else if (c >= 0x20) {
			return c;
		} else {
			return nextStringChar_rare(c);
		}
	}

	/**
	 * Decodes an escape sequence into the code point it represents. Escape
	 * sequences are uncommon in member names and string values, so keeping the
	 * decoding here lets {@link #nextStringChar}'s common path stay small
	 * enough for C2 to inline; escaped strings pay an extra call, which is
	 * acceptable.
	 */
	private int decodeEscape() {
		if (pos >= chars.length) {
			throw new JsonSyntaxException("Unterminated escape sequence at end of input");
		}
		char esc = chars[pos++];
		return switch (esc) {
			case '"', '\\', '/' -> esc;
			case 'b' -> '\b';
			case 'f' -> '\f';
			case 'n' -> '\n';
			case 'r' -> '\r';
			case 't' -> '\t';
			case 'u' -> {
				if (pos + 4 > chars.length) {
					throw new JsonSyntaxException("Incomplete Unicode escape sequence at end of input");
				}
				int value = 0;
				for (int i = 0; i < 4; i++) {
					char b = chars[pos++];
					value <<= 4;
					int digit = Character.digit(b, 16);
					if (digit == -1) {
						throw new JsonSyntaxException("Invalid hex digit in Unicode escape: '" + b + "'");
					} else {
						value |= digit;
					}
				}
				yield value;
			}
			default -> throw new JsonSyntaxException("Invalid escape: \\" + esc);
		};
	}

	/**
	 * The rare path of {@link #nextStringChar}, for an illegal character in a
	 * string. Because escape sequences are decoded into code points by
	 * {@link #decodeEscape}, this is the only place that can distinguish actual
	 * illegal characters from legal escape sequences. Malformed input is rare,
	 * so this can sit off the common path. This method never returns normally.
	 */
	private int nextStringChar_rare(int c) {
		throw new JsonSyntaxException("Invalid character in string: " + Integer.toHexString(c));
	}

	@Override
	public void skipStringChars(int n) {
		// Fast path: skip n plain characters with no quotes, escapes, control
		// characters, or surrogate pairs. Otherwise fall back to the default
		// implementation, which handles those cases character by character.
		int limit = pos + n;
		if (limit <= chars.length) {
			int i = pos;
			while (i < limit) {
				char c = chars[i];
				if (c == '"' || c == '\\' || c < 0x20 || (MIN_SURROGATE <= c && c <= MAX_SURROGATE)) {
					skipStringChars_rare(n);
					return;
				}
				i++;
			}
			if (i == limit) {
				pos = limit;
				return;
			}
		}
		skipStringChars_rare(n);
	}

	/**
	 * The rare path of {@link #skipStringChars}: the characters are not all
	 * plain, n is negative, or the string ends early. Fall back to the default
	 * implementation, which handles escapes, surrogate pairs, and end-of-string
	 * checks character by character. Member names rarely contain escapes or
	 * surrogate pairs, so the slower per-character path is acceptable when
	 * they do.
	 */
	private void skipStringChars_rare(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("Must skip a non-negative number of characters, got " + n);
		}
		JsonReader.super.skipStringChars(n);
	}

	@Override
	public void skipToEndOfString() {
		// Fast path: scan for the closing quote over plain characters. On an
		// escape or control character, fall back to the per-character loop,
		// which decodes escape sequences and rejects control characters.
		int p = pos;
		while (p < chars.length) {
			char c = chars[p];
			if (c == '"') {
				pos = p + 1;
				return;
			}
			if (c == '\\' || c < 0x20) {
				break;
			}
			p++;
		}
		while (nextStringChar() >= 0) { }
	}

	@Override
	public void consumeEndOfString() {
		// assert peekRawChar() == '"';
		pos++;
	}

	@Override
	public void close() {

	}

	@Override
	public String consumeString() {
		// We can do better than the default implementation
		int start = ++pos; // First actual character in the string's value
		while (pos < chars.length) {
			char c = chars[pos];
			if (c == '"') {
				String result = new String(chars, start, pos - start);
				pos++; // Skip closing quote
				return result;
			}
			if (c == '\\') {
				// Whoops, found an escape code. Fast path doesn't work.
				return consumeString_rare(start);
			}
			pos++;
		}
		throw new JsonSyntaxException("Unterminated string");
	}

	/**
	 * The rare path of {@link #consumeString}: the string contains an escape
	 * sequence, so back up to the opening quote and fall back to the
	 * character-by-character default implementation. Strings with escapes are
	 * uncommon, so the slower fallback is acceptable.
	 */
	private String consumeString_rare(int start) {
		pos = start - 1; // Back up to the opening quote
		return JsonReader.super.consumeString();
	}

	@Override
	public void validateSyntax(CharSequence expectedCharacters) {
		if (expectedCharacters.length() > chars.length - pos) {
			throw new JsonSyntaxException("Unexpected end of input; expecting '" + expectedCharacters + "'");
		} else {
			for (int i = 0; i < expectedCharacters.length(); i++) {
				if (chars[pos + i] != expectedCharacters.charAt(i)) {
					throw new JsonSyntaxException("Unexpected character '" + chars[pos + i] +
						"'; expecting '" + expectedCharacters.charAt(i) + "'");
				}
			}
			pos += expectedCharacters.length();
		}
	}

	@Override
	public String previewString(int requestedLength) {
		int actualLength = min(requestedLength, chars.length - pos - 1);
		return new String(chars, pos, actualLength);
	}

	@Override
	public long currentOffset() {
		return pos;
	}

	@Override
	public Token peekRawToken() {
		return Token.startingWith(peekRawChar());
	}

	private class CharArraySequence implements CharSequence {
		private final int start;
		private final int stop;

		public CharArraySequence(int start, int stop) {
			this.start = start;
			this.stop = stop;
		}

		@Override
		public int length() {
			return stop - start;
		}

		@Override
		public char charAt(int index) {
			return chars[start + index];
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			if (start < 0 || end > length() || start > end) {
				throw new IndexOutOfBoundsException();
			} else {
				return new CharArraySequence(this.start + start, this.start + end);
			}
		}

		@Override
		public String toString() {
			return new String(chars, start, length());
		}
	}
}
