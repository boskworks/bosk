package works.bosk.boson.codec.io;

/**
 * A simple {@link ChunkFiller} that returns a single chunk backed by a given byte array.
 */
public class ByteArrayChunkFiller implements ChunkFiller {
	final ByteChunk chunk;

	public ByteArrayChunkFiller(byte[] bytes) {
		// No need for carryover because there's only one chunk.
		this.chunk = new ByteChunk(bytes, 0, bytes.length);
	}

	@Override
	public ByteChunk nextChunk() {
		return chunk;
	}

	@Override
	public void recycleChunk(ByteChunk chunk) {
		assert chunk == this.chunk;
	}

	@Override
	public void close() {
		// Nothing to do
	}
}
