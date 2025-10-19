package works.bosk.boson.codec.io;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A high-performance lightweight CharSequence implementation
 * that wraps a byte array containing only 7-bit ASCII characters.
 * <p>
 * If the buffer contains non-ASCII characters, weirdness will ensue.
 */
final class AsciiChunkCharSequence implements CharSequence {
	private final byte[] buffer;
	private final int start;
	private final int length;

	private AsciiChunkCharSequence(byte[] buffer, int start, int length) {
		this.buffer = buffer;
		this.start = start;
		this.length = length;
	}

	AsciiChunkCharSequence(ByteChunk chunk, int start, int length) {
		assert start >= 0 && length >= 0 && start + length <= chunk.stop();
		this(chunk.bytes(), start, length);
	}

	@Override
	public int length() {
		return length;
	}

	@Override
	public char charAt(int index) {
		return (char) buffer[start + index];
	}

	@Override
	public CharSequence subSequence(int start, int end) throws IndexOutOfBoundsException {
		if (start < 0 || end > length) {
			throw new IndexOutOfBoundsException();
		}
		return new AsciiChunkCharSequence(buffer, this.start + start, end - start);
	}

	@Override
	public String toString() {
		return new String(buffer, start, length, UTF_8);
	}
}
