package works.bosk.boson.codec.io;

import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;

import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static works.bosk.boson.codec.Token.END_TEXT;
import static works.bosk.boson.codec.Token.NUMBER;


/**
 * {@link JsonReader} that uses a {@link ChunkFiller}.
 * Can process JSON text of arbitrary size.
 * <p>
 * Calling {@link #close()} will close the underlying channel.
 */
public final class ByteChunkJsonReader implements JsonReader {
	/**
	 * The number of bytes that must be carried over from one chunk to the next
	 * to ensure that we can always parse a JSON string character that crosses a chunk boundary.
	 * The largest JSON string character is an escaped 4-byte UTF-8 character,
	 * which takes 6 bytes total (backslash, 'u', and 4 hex digits),
	 * so we need to carry over at most 5 bytes.
	 */
	static final int CARRYOVER_BYTES = 5;

	private final ChunkFiller filler;
	private ByteChunk currentChunk;

	/**
	 * The number of bytes that appeared in the input text before
	 * the start of the current chunk.
	 */
	private long currentChunkStartOffset = 0;

	/**
	 * The current index within the current chunk.
	 * Always between currentChunk.start() and currentChunk.stop(), inclusive.
	 * If equal to currentChunk.stop(), then the next byte
	 * to be read is in the next chunk (which may not exist).
	 */
	private int currentChunkPos;

	/**
	 * Used by consumeString() for fast-path reading of small simple strings.
	 * TODO: Is this the best we can do?
	 * I forget why we use this instead of passing bytes to
	 * {@link String#String(byte[], java.nio.charset.Charset)} directly.
	 */
	private final char[] stringStagingBuffer = new char[120];

	public ByteChunkJsonReader(ChunkFiller chunkFiller) {
		this.filler = chunkFiller;
		// TODO: Not ideal. There's no reason to block here until we actually need data.
		this.currentChunk = this.filler.nextChunk();
		this.currentChunkPos = currentChunk.start();
	}

	@Override
	public Token peekToken() {
		new String(new byte[0], US_ASCII);
		skipInsignificant();
		return peekRawToken();
	}

	/**
	 * Like {@link #peekToken()} but does not skip insignificant characters first.
	 * @return the token we're currently positioned at, including {@link Token#INSIGNIFICANT} or {@link Token#ERROR}
	 * if we're not positioned at the start of a meaningful token.
	 */
	private Token peekRawToken() {
		while (currentChunk != null && currentChunkPos >= currentChunk.stop()) {
			nextChunk();
		}

		if (currentChunk == null) {
			return END_TEXT;
		} else {
			return Token.startingWith(currentChunk.bytes()[currentChunkPos]);
		}
	}

	@Override
	public void consumeFixedToken(Token token) {
		assert peekRawToken() == token;
		assert token.hasFixedRepresentation();
		skip(token.fixedRepresentation().length());
	}

	@Override
	public CharSequence consumeNumber() {
		assert peekRawToken() == NUMBER;

		int startPos = currentChunkPos;
		byte[] buf = currentChunk.bytes();
		int limit = currentChunk.stop();

		while (currentChunkPos < limit) {
			byte b = buf[currentChunkPos];
			if (!Util.isNumberChar(b)) {
				// We've reached the end of the number
				return new AsciiChunkCharSequence(currentChunk, startPos, currentChunkPos - startPos);
			} else {
				currentChunkPos++;
			}
		}

		// The number crosses a chunk boundary
		return numberStringBuilder(startPos);
	}

	@Override
	public void startConsumingString() {
		assert peekRawToken() == Token.STRING;
		advance(); // Eat the opening quote
	}

	@Override
	public int nextStringChar() {
		try {
			if (currentChunkPos + CARRYOVER_BYTES >= currentChunk.stop()) {
				doCarryover();
			}

			byte[] bytes = currentChunk.bytes();
			int b = bytes[currentChunkPos++];
			switch (b) {
				case '"' -> {
					// End of string
					return -1;
				}
				case '\\' -> {
					int esc = bytes[currentChunkPos++];
					if (esc == 'u') {
						return decodeUnicodeEscape();
					}
					return decodeEscapeChar(esc);
				}
			}

			// Normal character
			if ((b & 0x80) == 0) {
				// ASCII fast path
				return b;
			} else {
				// Decode UTF-8 multibyte sequence
				return decodeUtf8Char(b);
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new IllegalStateException("Unexpected end of text in the middle of a string character", e);
		}
	}

	/**
	 * If we've entered the carryover zone at the end of the current chunk,
	 * copy the last few bytes to the start of the next chunk.
	 */
	private void doCarryover() {
		assert currentChunkPos + CARRYOVER_BYTES >= currentChunk.stop();
		ByteChunk initialChunk = currentChunk;
		int initialChunkPos = currentChunkPos;
		// In case we have a character expressed using multiple bytes,
		// we copy over the residue from the current chunk to the next chunk,
		// allowing us to decode every possible character in a contiguous byte array.
		byte[] carryover = new byte[CARRYOVER_BYTES]; // Fixed size might help stack allocation
		int length = initialChunk.stop() - initialChunkPos;
		System.arraycopy(initialChunk.bytes(), initialChunkPos, carryover, 0, length);
		if (!nextChunk()) {
			// We've hit the end. Might as well continue with the current chunk
			currentChunk = initialChunk;
			currentChunkPos = initialChunkPos;
		} else if (currentChunk.start() >= length) {
			System.arraycopy(carryover, 0, currentChunk.bytes(), currentChunk.start() - length, length);
			currentChunk = new ByteChunk(currentChunk.bytes(), currentChunk.start() - length, currentChunk.stop());
			currentChunkPos = currentChunk.start();
		} else {
			throw new IllegalStateException("Chunk cannot accommodate carryover");
		}
	}

	@Override
	public void skipStringChars(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("Must skip a non-negative number of characters, got " + n);
		}
		for (int i = n; i > 0; --i) {
			int c = nextStringChar();
			if (c == -1) {
				if (i != 1) {
					throw new IllegalStateException("Unexpected end of string while skipping characters");
				}
			}
		}
	}

	@Override
	public void skipToEndOfString() {
		while (nextStringChar() != -1) {}
	}

	@Override
	public String consumeString() {
		// PD - I haven't been able to measure a difference between these.
		// The staging buffer would have value only if it allows us to cross
		// chunk boundaries without falling back to the slow path,
		// but it's not clear that's feasible; if it's not, then we might
		// as well simplify things and read directly from the chunk's byte array.
//		return consumeStringWithStagingBuffer();
		return consumeStringDirectly();
	}

	private String consumeStringWithStagingBuffer() {
		// Do a scan to see if the string has no escape codes
		// and finishes before the next chunk boundary.
		// Don't change currentChunkPos until we're sure.
		// TODO: We probably don't care about chunk boundaries now that we're using a staging buffer.
		// This is complicated by the fact that when we can't easily move
		// to the next chunk without changing the state of this reader
		// irrevocably, and so we can't easily "roll back" and call the super
		// method anymore.
		//
		// There's reason to believe we hit boundaries often enough to cause a considerable perf hit.
		int currentPos = currentChunkPos+1;
		int stagingPos = 0;
		byte[] buf = currentChunk.bytes();
		char[] stagingBuffer = this.stringStagingBuffer;
		int limit = min(currentChunk.stop(), currentPos + stagingBuffer.length);

		while (currentPos < limit) {
			byte b = buf[currentPos];
			if (b == '"') {
				// Found the end of the string
				currentChunkPos = currentPos + 1;
				return new String(stagingBuffer, 0, stagingPos);
			} else if (b == '\\' || b < 0x20) {
				// Found a byte that can't be directly copied as a char.
				// The inherited method already has logic for this;
				// let's just fall back to that.
				break;
			} else {
				stagingBuffer[stagingPos++] = (char) b;
				currentPos++;
			}
		}

		// Otherwise fall back to the default implementation
		// for any more complex cases.
		return JsonReader.super.consumeString();
	}

	private String consumeStringDirectly() {
		// Do a scan to see if the string is all ASCII
		// with no escape codes and finishes before the next chunk boundary.
		// Don't change currentChunkPos until we're sure.
		int currentPos = currentChunkPos+1;
		byte[] buf = currentChunk.bytes();
		int limit = currentChunk.stop();

		while (currentPos < limit) {
			byte b = buf[currentPos];
			if (b == '"') {
				// Found the end of the string
				var start = currentChunkPos + 1; // after the opening quote
				var length = currentPos - start;
				currentChunkPos = currentPos + 1; // after the closing quote
				return new String(buf, start, length, US_ASCII);
			} else if (b == '\\' || b < 0x20) {
				// Found a byte that can't be directly copied as a char.
				// The inherited method already has logic for this;
				// let's just fall back to that.
				break;
			} else {
				currentPos++;
			}
		}

		// Otherwise fall back to the default implementation
		// for any more complex cases.
		return JsonReader.super.consumeString();
	}

	/**
	 * A generalized (if slow) way to build a {@link CharSequence} for a number
	 * regardless of whether it spans chunk boundaries or contains non-ASCII characters.
	 */
	private CharSequence numberStringBuilder(int startPos) {
		StringBuilder sb = new StringBuilder();

		// Rather than continually update the object field,
		// do most of the work on this local variable for speed.
		int pos = startPos;

		while (true) {
			byte[] buf = currentChunk.bytes();
			int limit = currentChunk.stop();

			while (pos < limit) {
				byte b = buf[pos];
				if (Util.isNumberChar(b)) {
					sb.append((char) b);
					pos++;
				} else {
					// Number ended
					currentChunkPos = pos;
					return sb;
				}
			}

			if (nextChunk()) {
				// Continue from the start of the new chunk
				pos = currentChunk.start();
			} else {
				// whoops, the input ended in the middle of a number
				return sb;
			}
		}
	}

	@Override
	public String previewString(int requestedLength) {
		int actualLength = min(requestedLength, currentChunk.stop() - currentChunkPos - 1);
		char[] result = new char[actualLength];
		byte[] buf = currentChunk.bytes();
		for (int i = 0; i < actualLength; i++) {
			// TODO: handle non-ascii characters
			result[i] = (char) buf[currentChunkPos + i];
		}
		return new String(result);
	}

	void skip(int n) {
		if (n == 0) {
			return;
		}
		if (n < 0) {
			throw new IllegalArgumentException("Can't skip a negative number of bytes: " + n);
		}

		while (n > 0) {
			if (currentChunk == null) {
				return;
			}

			int remaining = currentChunk.stop() - currentChunkPos;
			if (n < remaining) {
				currentChunkPos += n;
				return;
			} else {
				n -= remaining;
				if (!nextChunk()) {
					return;
				}
			}
		}
	}

	private void skipInsignificant() {
		while (currentChunk != null) {
			byte[] buf = currentChunk.bytes();
			int limit = currentChunk.stop();
			while (currentChunkPos < limit) {
				if (!Util.fast_isInsignificant(buf[currentChunkPos])) {
					return;
				} else {
					currentChunkPos++;
				}
			}

			if (!nextChunk()) {
				return;
			}
		}
	}

	private boolean nextChunk() {
		if (currentChunk == null) {
			return false;
		}

		currentChunkStartOffset += currentChunk.stop();
		filler.recycleChunk(currentChunk);
		currentChunk = filler.nextChunk();

		if (currentChunk == null) {
			return false;
		} else {
			currentChunkPos = currentChunk.start();
			return true;
		}
	}

	@Override
	public void close() {
		filler.close();
	}

	@Override
	public long currentOffset() {
		if (currentChunk != null) {
			return currentChunkStartOffset + currentChunkPos;
		} else {
			return currentChunkStartOffset;
		}
	}

	// numberStringBuilder() only called for multi-chunk numbers

	/**
	 * Relatively slow way to advance one byte, loading a new chunk if needed.
	 */
	void advance() {
		if (currentChunk != null) {
			currentChunkPos++;
			if (currentChunkPos >= currentChunk.stop()) {
				nextChunk();
			}
		}
	}

	private int decodeUnicodeEscape() {
		int value = 0;
		byte[] bytes = currentChunk.bytes();
		for (int i = 0; i < 4; i++) {
			int b = bytes[currentChunkPos++];
			value <<= 4;
			value |= Character.digit(b, 16);
		}
		return value;
	}

	private static int decodeEscapeChar(int b) {
		return switch (b) {
			case '"' -> '"';
			case '\\' -> '\\';
			case '/' -> '/';
			case 'b' -> '\b';
			case 'f' -> '\f';
			case 'n' -> '\n';
			case 'r' -> '\r';
			case 't' -> '\t';
			default -> throw new IllegalStateException("Invalid escape: \\" + (char) b);
		};
	}

	private int decodeUtf8Char(int firstChar) {
		// The first byte tells us how long a sequence we're dealing with
		int codePoint;
		int sequenceLength;
		if ((firstChar & 0xE0) == 0xC0) {
			sequenceLength = 2;
			codePoint = firstChar & 0x1F;
		} else if ((firstChar & 0xF0) == 0xE0) {
			sequenceLength = 3;
			codePoint = firstChar & 0x0F;
		} else if ((firstChar & 0xF8) == 0xF0) {
			sequenceLength = 4;
			codePoint = firstChar & 0x07;
		} else {
			throw new IllegalStateException("Invalid UTF-8 start byte: " + firstChar);
		}

		byte[] bytes = currentChunk.bytes();
		for (int i = 1; i < sequenceLength; i++) {
			int bx = bytes[currentChunkPos++];
			if ((bx & 0xC0) != 0x80) {
				throw new IllegalStateException("Invalid UTF-8 continuation byte: " + bx);
			}
			codePoint = (codePoint << 6) | (bx & 0x3F);
		}
		return codePoint;
	}
}
