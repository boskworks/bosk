package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.Parameter;
import works.bosk.boson.codec.JsonReader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static works.bosk.boson.codec.io.ByteChunkJsonReader.MIN_CHUNK_SIZE;

public class AbstractJsonReaderTest {
	@Parameter
	Function<String, ? extends JsonReader> readerSupplier;

	protected JsonReader readerFor(String json) {
		return readerSupplier.apply(json);
	}

	@SuppressWarnings("unused") // Subclasses use this to parameterize tests
	static Stream<Function<String, ? extends JsonReader>> readerSuppliers() {
		return Stream.of(
			new ByteArray(),
			new ByteChunks(),
			new CharArray(),
			new CharArray() {
				@Override
				public JsonReader apply(String s) {
					return super.apply(s).withValidation();
				}

				@Override
				public String toString() {
					return "Validating " + super.toString();
				}
			}
		);
	}

	static class ByteArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			ByteArrayInputStream in = new ByteArrayInputStream(s.getBytes(UTF_8));
			return JsonReader.create(in);
		}

		@Override
		public String toString() {
			return "Byte array";
		}
	}

	static class ByteChunks implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return new ByteChunkJsonReader(new SynchronousChunkFiller(new ByteArrayInputStream(s.getBytes(UTF_8)), MIN_CHUNK_SIZE));
		}

		@Override
		public String toString() {
			return "Byte chunks";
		}
	}

	static class CharArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return JsonReader.create(s.toCharArray());
		}

		@Override
		public String toString() {
			return "Char array";
		}
	}
}
