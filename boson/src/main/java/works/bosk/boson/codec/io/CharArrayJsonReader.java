package works.bosk.boson.codec.io;

import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonFormatException;
import works.bosk.boson.exceptions.JsonSyntaxException;

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

	@Override
	public void consumeFixedToken(Token token) {
		assert peekRawToken() == token;
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
		assert peekRawToken() == Token.STRING;
		pos++; // Skip opening quote
	}

	@Override
	public int nextStringChar() {
		if (pos >= chars.length) {
			throw new JsonSyntaxException("Unterminated string at end of input");
		}
		char c = chars[pos++];
		if (Character.isSurrogate(c)) {
			if (pos >= chars.length) {
				throw new JsonSyntaxException("Unpaired UTF-8 surrogate at end of input");
			}
			return Character.toCodePoint(c, chars[pos++]);
		} else if (c == '"') {
			return END_OF_STRING;
		} else if (c == '\\') {
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
						value |= Character.digit(b, 16);
					}
					yield value;
				}
				default -> throw new JsonSyntaxException("Invalid escape: \\" + esc);
			};
		} else if (c >= 0x20) {
			return c;
		} else {
			// Because we decode backslash sequences into code points,
			// this is the only place we can distinguish actual illegal characters
			// from legal escape sequences.
			throw new JsonSyntaxException("Invalid character in string: " + Integer.toHexString(c));
		}
	}

	@Override
	public void skipToEndOfString() {
		while (nextStringChar() >= 0) { }
	}

	@Override
	public void close() {

	}

	@Override
	public String consumeString() {
		// We can do better than the default implementation
		int start = ++pos; // First actual character in the string's value
		int c;
		try {
			while (pos <= chars.length && (c = chars[pos]) != '"') {
				pos++;
				if (c == '\\') {
					// Whoops, found an escape code. Fast path doesn't work.
					pos = start - 1; // Back up to the opening quote
					return JsonReader.super.consumeString();
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new JsonSyntaxException("Unterminated string", e);
		}
		String result = new String(chars, start, pos - start);
		pos++; // Skip closing quote
		return result;
	}

	@Override
	public void validateCharacters(CharSequence expectedCharacters) {
		if (expectedCharacters.length() > chars.length - pos) {
			throw new JsonFormatException("Unexpected end of input; expecting '" + expectedCharacters + "'");
		} else {
			for (int i = 0; i < expectedCharacters.length(); i++) {
				if (chars[pos + i] != expectedCharacters.charAt(i)) {
					throw new JsonFormatException("Unexpected character '" + chars[pos + i] +
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
