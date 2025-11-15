package works.bosk.boson.codec.io;

import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;

import static java.lang.Math.min;

/**
 * A {@link JsonReader} that reads from a char array.
 * Useful for reading JSON text that is small enough to have been
 * fully loaded into memory already, like when reading from a String.
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
	public Token peekToken() {
		skipInsignificant();
		return Token.startingWith(peekChar());
	}

	/**
	 * @return NOT a code point!
	 */
	private int peekChar() {
		if (pos >= chars.length) {
			return -1;
		} else {
			return chars[pos];
		}
	}

	private void skipInsignificant() {
		while (Util.fast_isInsignificant(peekChar())) {
			pos++;
		}
	}

	@Override
	public void consumeFixedToken(Token token) {
		assert peekToken() == token;
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
		assert peekToken() == Token.STRING;
		pos++; // Skip opening quote
	}

	@Override
	public int nextStringChar() {
		if (pos >= chars.length) {
			return -1;
		}
		char c = chars[pos++];
		if (Character.isSurrogate(c)) {
			if (pos >= chars.length) {
				// Unpaired surrogate at the end of input.
				// We're doomed to get a parse error; might
				// as well return -1 to indicate the end of input
				// in hopes that will generate a useful error message.
				return -1;
			}
			return Character.toCodePoint(c, chars[pos++]);
		} else if (c == '"') {
			return -1;
		} else if (c == '\\') {
			if (pos >= chars.length) {
				// Unfinished backslash sequence at the end of input.
				// We're doomed to get a parse error; might
				// as well return -1 to indicate the end of input
				// in hopes that will generate a useful error message.
				return -1;
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
						// Incomplete Unicode escape at the end of input.
						yield -1;
					}
					int value = 0;
					for (int i = 0; i < 4; i++) {
						char b = chars[pos++];
						value <<= 4;
						value |= Character.digit(b, 16);
					}
					yield value;
				}
				default -> throw new IllegalStateException("Invalid escape: \\" + esc);
			};
		} else {
			return c;
		}
	}

	@Override
	public void skipStringChars(int n) {
		for (int i = 0; i < n; i++) {
			int c = nextStringChar();
			if (c == -1) {
				throw new IllegalStateException("Attempt to skip past end of string");
			}
		}
	}

	@Override
	public void skipToEndOfString() {
		while (nextStringChar() != -1) { }
	}

	@Override
	public void close() {

	}

	@Override
	public String consumeString() {
		// We can do better than the default implementation
		int start = ++pos; // First actual character in the string's value
		int c;
		while (pos <= chars.length && (c = chars[pos]) != '"') {
			pos++;
			if (c == '\\') {
				// Whoops, found an escape code. Fast path doesn't work.
				pos = start-1; // Back up to the opening quote
				return JsonReader.super.consumeString();
			}
		}
		String result = new String(chars, start, pos - start);
		pos++; // Skip closing quote
		return result;
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
