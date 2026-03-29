package works.bosk.boson.codec.io;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import works.bosk.boson.codec.JsonReader;
import works.bosk.junit.Injector;

import static java.nio.charset.StandardCharsets.UTF_8;

record JsonReaderValidateSyntaxInjector() implements Injector {
	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType == ReaderFactoryParameter.class;
	}

	@Override
	public List<ReaderFactoryParameter> values() {
		return List.of(
			new ReaderFactoryParameter("overlapped", (json, chunkSize) -> {
				var filler = new OverlappedPrefetchingChunkFiller(
					new java.io.ByteArrayInputStream(json.getBytes(UTF_8)),
					chunkSize, 2
				);
				return new ByteChunkJsonReader(filler);
			}),
			new ReaderFactoryParameter("synchronous", (json, chunkSize) -> {
				var filler = new SynchronousChunkFiller(
					new java.io.ByteArrayInputStream(json.getBytes(UTF_8)),
					chunkSize
				);
				return new ByteChunkJsonReader(filler);
			}),
			new ReaderFactoryParameter("char array", (json, _) -> {
				char[] chars = json.toCharArray();
				return new CharArrayJsonReader(chars);
			})
		);
	}

	interface ReaderFactory {
		JsonReader create(String json, int chunkSize);
	}

	record ReaderFactoryParameter(String name, ReaderFactory factory) {
		@Override
		public String toString() {
			return name;
		}
	}
}
