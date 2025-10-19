package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.CARRYOVER_BYTES;

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
		byte[] data = "abc".getBytes();
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data), 2+CARRYOVER_BYTES, 1)) {
			ByteChunk buf1 = prefetcher.nextChunk();
			assertEquals(2, buf1.length());
			assertEquals('a', buf1.bytes()[buf1.start()]);
			assertEquals('b', buf1.bytes()[buf1.start()+1]);
			prefetcher.recycleChunk(buf1);

			ByteChunk buf2 = prefetcher.nextChunk();
			assertEquals(1, buf2.length());
			assertEquals('c', buf2.bytes()[buf2.start()]);
			prefetcher.recycleChunk(buf2);

			assertNull(prefetcher.nextChunk());
		}
	}

	@Test
	void multipleTinyBuffers() {
		byte[] data = "abcdef".getBytes();
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data), 1+CARRYOVER_BYTES, 3)) {
			List<Byte> read = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				ByteChunk buf = prefetcher.nextChunk();
				assertEquals(1, buf.length());
				read.add(buf.bytes()[buf.start()]);
				prefetcher.recycleChunk(buf);
			}
			assertNull(prefetcher.nextChunk());
			assertEquals(List.of((byte)'a', (byte)'b', (byte)'c', (byte)'d', (byte)'e', (byte)'f'), read);
		}
	}

	@Test
	void recycleAllowsReuse() {
		byte[] data = "xy".getBytes();
		try (ChunkFiller prefetcher = new OverlappedPrefetchingChunkFiller(new ByteArrayInputStream(data), 1+CARRYOVER_BYTES, 1)) {
			ByteChunk buf1 = prefetcher.nextChunk();
			assertEquals(1, buf1.length());
			assertEquals('x', buf1.bytes()[buf1.start()]);
			prefetcher.recycleChunk(buf1);

			ByteChunk buf2 = prefetcher.nextChunk();
			assertSame(buf1.bytes(), buf2.bytes());
			assertEquals(1, buf2.length());
			assertEquals('y', buf2.bytes()[buf2.start()]);
			prefetcher.recycleChunk(buf2);

			assertNull(prefetcher.nextChunk());
		}
	}

}
