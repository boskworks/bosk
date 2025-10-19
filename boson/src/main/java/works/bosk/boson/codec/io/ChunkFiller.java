package works.bosk.boson.codec.io;

/**
 * Supplies a series of byte chunks for processing
 * and allows them to be reused when the caller is finished.
 * This is a way for an IO source to produce data for consumption by {@link ByteChunkJsonReader}.
 * <p>
 * In general, the results of calling {@link #nextChunk()} multiple times
 * without recycling the previous chunk are undefined.
 * The implementation may hang, may overwrite the chunk previously returned, etc.
 * <p>
 * TODO: If we don't support multiple chunks being processed at once anyway,
 * then {@link #recycleChunk(ByteChunk)} isn't really necessary,
 * and could be inferred automatically upon the next call to {@link #nextChunk()}.
 */
public interface ChunkFiller extends AutoCloseable {
	ByteChunk nextChunk();

	void recycleChunk(ByteChunk chunk);

	@Override void close(); // No throws Exception
}
