package works.bosk.boson.codec.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static works.bosk.boson.codec.io.ByteChunkJsonReader.CARRYOVER_BYTES;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.MIN_CHUNK_SIZE;

/**
 * Uses a virtual thread to read from a channel in the background
 * while the foreground thread processes previously read data.
 * This ensures that the parsing runs at the full speed of either
 * the processing or the I/O, whichever is slower.
 * <p>
 * Calling {@link #close()} will close the underlying channel.
 */
public final class OverlappedPrefetchingChunkFiller implements ChunkFiller {
	private final InputStream stream;
	private final BlockingQueue<byte[]> emptyBuffers;
	private final BlockingQueue<ByteChunk> filledBuffers;
	private final Thread backgroundThread;

	public OverlappedPrefetchingChunkFiller(InputStream stream) {
		// In microbenchmarks, we can outperform Jackson with a 2MB buffer,
		// but that's pretty extreme.

		// We aim to use an integer number of memory pages for the array object.
		// It's not clear whether this is better than using an integer number of
		// pages for the array _contents_.
		int bufferSize = 10*4096 - 16; // 16 = array header
		this(stream, bufferSize, 2);
	}

	/**
	 * @param numBuffers if only 1, no overlapping will occur.
	 */
	public OverlappedPrefetchingChunkFiller(InputStream stream, int bufferSize, int numBuffers) {
		assert bufferSize >= MIN_CHUNK_SIZE: "Buffer size must be at least " + MIN_CHUNK_SIZE;
		this.stream = stream;
		this.emptyBuffers = new ArrayBlockingQueue<>(numBuffers);
		this.filledBuffers = new ArrayBlockingQueue<>(numBuffers);

		for (int i = 0; i < numBuffers; i++) {
			emptyBuffers.add(new byte[bufferSize]);
		}

		backgroundThread = Thread.ofVirtual()
			.name("overlapped-prefetcher")
			.start(this::fillBuffers);
	}

	private void fillBuffers() {
		try {
			while (true) {
				byte[] buffer = emptyBuffers.take();
				int length = stream.read(buffer, CARRYOVER_BYTES, buffer.length - CARRYOVER_BYTES);
				if (length == -1) {
					filledBuffers.put(EOF_SENTINEL);
					break;
				}

				filledBuffers.put(new ByteChunk(buffer, CARRYOVER_BYTES, CARRYOVER_BYTES + length));
			}
		} catch (ClosedChannelException _) {
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * @return the next filled buffer, or null if EOF.
	 */
	@Override
	public ByteChunk nextChunk() {
		ByteChunk result;
		try {
			result = filledBuffers.take();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		if (result == EOF_SENTINEL) {
			return null;
		} else {
			return result;
		}
	}

	/**
	 * Recycle a buffer after use.
	 */
	@Override
	public void recycleChunk(ByteChunk chunk) {
		var succeeded = emptyBuffers.offer(chunk.bytes());
		if (!succeeded) {
			LOGGER.debug("Buffer pool full, discarding buffer");
		}
	}

	@Override
	public void close() {
		backgroundThread.interrupt();
		try {
			stream.close();
		} catch (IOException _) {}
	}

	private static final ByteChunk EOF_SENTINEL = new ByteChunk(new byte[0], 0, 0);
	private static final Logger LOGGER = LoggerFactory.getLogger(OverlappedPrefetchingChunkFiller.class);
}
