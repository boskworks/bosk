package works.bosk.boson.codec.io;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simple {@link ChunkFiller} that returns a single chunk backed by a given byte array.
 */
public class ByteArrayChunkFiller implements ChunkFiller {
	final ByteChunk chunk;
	final AtomicBoolean isConsumed = new AtomicBoolean(false);

	public ByteArrayChunkFiller(byte[] bytes) {
		// No need for carryover because there's only one chunk.
		this.chunk = new ByteChunk(bytes, 0, bytes.length);
	}

	@Override
	public ByteChunk nextChunk() {
		return isConsumed.getAndSet(true) ? null : chunk;
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
