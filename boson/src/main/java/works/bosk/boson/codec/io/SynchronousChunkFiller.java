package works.bosk.boson.codec.io;

import java.io.IOException;
import java.io.InputStream;

import static works.bosk.boson.codec.io.ByteChunkJsonReader.CARRYOVER_BYTES;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.MIN_CHUNK_SIZE;

/**
 * A {@link ChunkFiller} that reads chunks on demand from an {@link InputStream}.
 * This offers simplicity, and eliminates the overhead of the background thread
 * and queue used by {@link OverlappedPrefetchingChunkFiller},
 * but the drawback is that it cannot possibly run at the full speed
 * of whichever is the limiting factor (processing or I/O)
 * because the two operations are interleaved instead of overlapped.
 */
public class SynchronousChunkFiller implements ChunkFiller {
	final InputStream stream;
	final byte[] buffer;

	public SynchronousChunkFiller(InputStream stream) {
		this(stream, 40_000);
	}

	SynchronousChunkFiller(InputStream stream, int bufferSize) {
		assert bufferSize >= MIN_CHUNK_SIZE: "Buffer size must be at least " + MIN_CHUNK_SIZE;
		this.stream = stream;
		buffer = new byte[bufferSize];
	}

	@Override
	public ByteChunk nextChunk() {
		int length;
		try {
			length = stream.read(buffer, CARRYOVER_BYTES, buffer.length - CARRYOVER_BYTES);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		if (length == -1) {
			return null;
		}

		return new ByteChunk(buffer, CARRYOVER_BYTES, CARRYOVER_BYTES + length);
	}

	@Override
	public void recycleChunk(ByteChunk chunk) {
		assert chunk.bytes() == this.buffer;
	}

	@Override
	public void close() {
		try {
			stream.close();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
