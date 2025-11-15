package works.bosk.boson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import works.bosk.boson.TestUtils.JustScalars;
import works.bosk.boson.TestUtils.Month;
import works.bosk.boson.TestUtils.OneOfEach;
import works.bosk.boson.codec.CodecBuilder;
import works.bosk.boson.codec.Parser;
import works.bosk.boson.codec.io.ByteChunkJsonReader;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.codec.io.OverlappedPrefetchingChunkFiller;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;
import static works.bosk.boson.TestUtils.JUST_SCALARS;
import static works.bosk.boson.TestUtils.ONE_OF_EACH;
import static works.bosk.boson.mapping.TypeMap.Settings.DEFAULT;

@BenchmarkMode(Throughput)
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations = 8, time = 1)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
public class ParseBenchmark {
	private char[] json;
	private ObjectReader objectReader;
	private ObjectReader listReader;
	private ManualTest manualTest;
	private Parser interpreter;
	private Parser interpreterExperimental;
	private Parser compiled;
	private Parser compiledExperimental;
	private Parser listParser;

	@Setup(Level.Iteration) // Called once per iteration
	public void setup() {
		BoundType listOfOneOfEach = new BoundType(List.class, DataType.of(OneOfEach.class));
		Class<?> targetClass;
		if (true) {
			targetClass = OneOfEach.class;
			json = ONE_OF_EACH.toCharArray();
		} else {
			targetClass = JustScalars.class;
			json = JUST_SCALARS;

		}
		var objectMapper = new ObjectMapper();
		objectReader = objectMapper.readerFor(targetClass);
		manualTest = new ManualTest();

		DataType targetType = DataType.of(targetClass);

		TypeScanner defaultTS = new TypeScanner(DEFAULT);
		defaultTS.specify(DataType.of(Month.class), Month.specNode());
		defaultTS.scan(targetType);
		TypeMap defaultTypeMap = defaultTS.build();
		JsonValueSpec defaultSpec = defaultTypeMap.get(targetType);
		interpreter = CodecBuilder.using(defaultTypeMap)
			.buildInterpreter().parserFor(defaultSpec);
		compiled = CodecBuilder.using(defaultTypeMap)
			.buildCompiled().parserFor(defaultSpec);

		TypeScanner experimentalTS = new TypeScanner(DEFAULT.withFewerSwitches());
		experimentalTS.specify(DataType.of(Month.class), Month.specNode());
		experimentalTS.scan(targetType);
		experimentalTS.scan(listOfOneOfEach);
		var experimentalTypeMap = experimentalTS.build();
		JsonValueSpec experimentalSpec = experimentalTypeMap.get(targetType);
		interpreterExperimental = CodecBuilder.using(experimentalTypeMap)
			.buildInterpreter().parserFor(experimentalSpec);
		compiledExperimental = CodecBuilder.using(experimentalTypeMap)
			.buildCompiled().parserFor(experimentalSpec);

		listReader = objectMapper.readerForListOf(OneOfEach.class);
		listParser = CodecBuilder.using(experimentalTypeMap).buildCompiled().parserFor(experimentalTypeMap.get(listOfOneOfEach));
	}

	@Benchmark
	public Object jackson() throws IOException {
		return objectReader.readValue(new java.io.CharArrayReader(json));
	}

	@Benchmark
	public Object manual() throws IOException {
		manualTest.init();
		return manualTest.parse();
	}

//	@Benchmark
	public Object interpreter_default() throws IOException {
		return interpreter.parse(new CharArrayJsonReader(json));
	}

//	@Benchmark
	public Object interpreter_experimental() throws IOException {
		return interpreterExperimental.parse(new CharArrayJsonReader(json));
	}

//	@Benchmark
	public Object compiled_default() throws IOException {
		return compiled.parse(new CharArrayJsonReader(json));
	}

	@Benchmark
	public Object compiled_experimental() throws IOException {
		return compiledExperimental.parse(new CharArrayJsonReader(json));
	}

	@Benchmark
	public Object jackson_list() throws IOException {
		Path file = Path.of(BIG_FILE).toAbsolutePath();
		try (var in = new FileInputStream(file.toFile())) {
			return listReader.readValue(in);
		}
	}

	@Benchmark
	public Object compiled_list() throws IOException {
		Path file = Path.of(BIG_FILE).toAbsolutePath();
		try (
			var in = new FileInputStream(file.toFile());
		) {
			return listParser.parse(new ByteChunkJsonReader(new OverlappedPrefetchingChunkFiller(in)));
		}
	}

	static final String BIG_FILE = "build/bigfiles/100k.json";
}
