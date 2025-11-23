package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.CARRYOVER_BYTES;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.MIN_CHUNK_SIZE;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OverlappedPrefetchingChunkFillerTest {
	@Test
	@Order(1) // If this doesn't work, we're pretty hosed
	void eof() {
		byte[] data = new byte[0];
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data))) {
			assertNull(prefetcher.nextChunk());
		}
	}

	@Test
	void singleSmallBuffer() {
		int bytesPerChunk = MIN_CHUNK_SIZE - CARRYOVER_BYTES;
		// Two full chunks plus one byte
		var ones = "1".repeat(bytesPerChunk);
		var twos = "2".repeat(bytesPerChunk);
		byte[] data = (ones + twos + "4").getBytes(UTF_8);
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data), MIN_CHUNK_SIZE, 1)) {
			ByteChunk buf1 = prefetcher.nextChunk();
			assertEquals(bytesPerChunk, buf1.length());
			assertArrayEquals(ones.getBytes(UTF_8), Arrays.copyOfRange(buf1.bytes(), buf1.start(), buf1.stop()));
			prefetcher.recycleChunk(buf1);

			ByteChunk buf2 = prefetcher.nextChunk();
			assertEquals(bytesPerChunk, buf2.length());
			assertEquals(twos, new String(buf2.bytes(), buf2.start(), buf2.length(), UTF_8));
			prefetcher.recycleChunk(buf2);

			ByteChunk buf3 = prefetcher.nextChunk();
			assertEquals(1, buf3.length());
			assertEquals("4", new String(buf3.bytes(), buf3.start(), buf3.length(), UTF_8));
			prefetcher.recycleChunk(buf3);

			assertNull(prefetcher.nextChunk());
		}
	}

	@Test
	void multipleTinyBuffers() {
		int bytesPerChunk = MIN_CHUNK_SIZE - CARRYOVER_BYTES;
		byte[] data = "abcdef".repeat(bytesPerChunk).getBytes(UTF_8);
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data), MIN_CHUNK_SIZE, 3)) {
			for (int i = 0; i < bytesPerChunk; i++) {
				ByteChunk buf = prefetcher.nextChunk();
				assertEquals(6, buf.length());
				assertArrayEquals("abcdef".getBytes(UTF_8), Arrays.copyOfRange(buf.bytes(), buf.start(), buf.stop()));
				prefetcher.recycleChunk(buf);
			}
			assertNull(prefetcher.nextChunk());
		}
	}

	@Test
	void recycleAllowsReuse() {
		int bytesPerChunk = MIN_CHUNK_SIZE - CARRYOVER_BYTES;
		var xs = "x".repeat(bytesPerChunk);
		var ys = "y".repeat(bytesPerChunk);
		byte[] data = (xs+ys).getBytes(UTF_8);
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data), MIN_CHUNK_SIZE, 1)) {
			ByteChunk buf1 = prefetcher.nextChunk();
			assertEquals(bytesPerChunk, buf1.length());
			assertArrayEquals(xs.getBytes(UTF_8), Arrays.copyOfRange(buf1.bytes(), buf1.start(), buf1.stop()));
			prefetcher.recycleChunk(buf1);

			ByteChunk buf2 = prefetcher.nextChunk();
			assertSame(buf1.bytes(), buf2.bytes());
			assertEquals(bytesPerChunk, buf2.length());
			assertEquals(ys, new String(buf2.bytes(), buf2.start(), buf2.length(), UTF_8));
			prefetcher.recycleChunk(buf2);

			assertNull(prefetcher.nextChunk());
		}
	}

}
