package works.bosk.boson.codec.io;

/**
 * @param bytes the byte array containing the chunk data.
 *              May be modified by {@link ByteChunkJsonReader}.
 * @param start the index of the first byte in the chunk containing data.
 *              Unless you're implementing carryover logic,
 *              or otherwise know exactly what you're doing,
 *              this should be at least {@link ByteChunkJsonReader#CARRYOVER_BYTES CARRYOVER_BYTES}.
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
