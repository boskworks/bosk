package works.bosk.boson.codec.io;

/**
 * @param start the index of the first byte in the chunk containing data
 * @param stop  the index one past the last byte in the chunk containing data
 */
public record ByteChunk(
	byte[] bytes,
	int start,
	int stop
) {
	int length() {
		return stop - start;
	}
}
