package works.bosk.boson.codec.io;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.function.Function;
import works.bosk.boson.codec.JsonReader;
import works.bosk.junit.Injector;

import static java.nio.charset.StandardCharsets.UTF_8;

record JsonReaderInjector() implements Injector {
	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType == Function.class;
	}

	@Override
	public List<Function<String, JsonReader>> values() {
		return List.of(
			new ByteArray(),
			new ByteChunks(),
			new CharArray(),
			new ValidatingCharArray()
		);
	}

	static class ByteArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return JsonReader.create(new java.io.ByteArrayInputStream(s.getBytes(UTF_8)));
		}

		@Override
		public String toString() {
			return "ByteArray";
		}
	}

	static class ByteChunks implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return new ByteChunkJsonReader(
				new SynchronousChunkFiller(
					new java.io.ByteArrayInputStream(s.getBytes(UTF_8)),
					ByteChunkJsonReader.MIN_CHUNK_SIZE
				)
			);
		}

		@Override
		public String toString() {
			return "ByteChunks";
		}
	}

	static class CharArray implements Function<String, JsonReader> {
		@Override
		public JsonReader apply(String s) {
			return JsonReader.create(s.toCharArray());
		}

		@Override
		public String toString() {
			return "CharArray";
		}
	}

	static class ValidatingCharArray extends CharArray {
		@Override
		public JsonReader apply(String s) {
			return super.apply(s).withValidation();
		}

		@Override
		public String toString() {
			return "ValidatingCharArray";
		}
	}
}
