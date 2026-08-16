package works.bosk.boson.codec.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import works.bosk.boson.TestUtils.Month;
import works.bosk.boson.TestUtils.OneOfEach;
import works.bosk.boson.codec.CodecBuilder;
import works.bosk.boson.codec.Parser;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.mapping.TypeMap.Settings.DEFAULT;

/**
 * Exercises the compiled parser through {@link ByteChunkJsonReader}, including
 * across chunk boundaries, on documents large enough that chunking matters.
 */
class ByteChunkCompiledParseTest {
	@Test
	void parsesHundredThousandRecords() throws IOException {
		List<OneOfEach> expected = randomRecords(100_000);
		byte[] json = jsonBytes(expected);

		List<OneOfEach> parsed = parse(json, 40_000);

		assertEquals(expected, parsed);
	}

	@Test
	void parsesAcrossFrequentChunkBoundaries() throws IOException {
		List<OneOfEach> expected = randomRecords(1_000);
		byte[] json = jsonBytes(expected);

		List<OneOfEach> parsed = parse(json, ByteChunkJsonReader.MIN_CHUNK_SIZE);

		assertEquals(expected, parsed);
	}

	@SuppressWarnings("unchecked")
	private static List<OneOfEach> parse(byte[] json, int bufferSize) throws IOException {
		Parser parser = compiledListParser();
		try (
			var reader = new ByteChunkJsonReader(new SynchronousChunkFiller(new ByteArrayInputStream(json), bufferSize));
		) {
			return (List<OneOfEach>) parser.parse(reader);
		}
	}

	private static List<OneOfEach> randomRecords(int count) {
		var r = new Random(123);
		return Stream
			.generate(() -> OneOfEach.random(r))
			.limit(count)
			.toList();
	}

	private static byte[] jsonBytes(List<OneOfEach> records) throws IOException {
		var out = new ByteArrayOutputStream();
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, records);
		return out.toByteArray();
	}

	private static Parser compiledListParser() {
		BoundType listOfOneOfEach = new BoundType(List.class, DataType.of(OneOfEach.class));
		TypeScanner ts = new TypeScanner(DEFAULT);
		ts.specify(DataType.of(Month.class), Month.specNode());
		ts.scan(DataType.of(OneOfEach.class));
		ts.scan(listOfOneOfEach);
		TypeMap tm = ts.build();
		JsonValueSpec spec = tm.get(listOfOneOfEach);
		return CodecBuilder.using(tm).buildCompiled().parserFor(spec);
	}
}
