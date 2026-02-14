package works.bosk.boson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;
import works.bosk.boson.mapping.Nullable;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.DataType;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static java.lang.Math.min;
import static java.lang.invoke.MethodHandles.explicitCastArguments;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestUtils {
	public static final String ONE_OF_EACH = """
		{
			"nullField": null,
			"trueField": true,
			"falseField": false,
			"integerField": 123,
			"realField": 3.14,
			"stringField": "hello 😎",
			"stringArrayField": ["one", "two", "three"],
			"mapField": {
				"SECONDS": 1.0,
				"MILLISECONDS": 1000.0
			},
			"monthField": 4
		}
		""";

	public static final char[] JUST_SCALARS = """
		{
			"trueField": true,
			"falseField": false,
			"integerField": 123,
			"realField": 3.14,
			"stringField": "hello 😎"
		}
		""".toCharArray();

	public static final String COMPUTED_FIELD_VALUE = "computed!";
	public static final String ABSENT_FIELD_VALUE = "absent!";

	public static OneOfEach expectedOneOfEach() throws IOException {
		OneOfEach parsed = new ObjectMapper().readerFor(OneOfEach.class).readValue(new StringReader(ONE_OF_EACH));
		return parsed
			.withComputedField(COMPUTED_FIELD_VALUE)
			.withMaybeAbsentField(ABSENT_FIELD_VALUE)
			;
	}

	public static JustScalars expectedScalars() throws IOException {
		return new ObjectMapper().readerFor(JustScalars.class).readValue(new StringReader(ONE_OF_EACH));
	}

	public record OneOfEach(
		@Nullable String nullField,
		boolean trueField,
		boolean falseField,
		long integerField,
		double realField,
		String stringField,
		List<String> stringArrayField,
		Map<TimeUnit, BigDecimal> mapField,
		Month monthField,
		String computedField,
		@JsonInclude(NON_NULL) String maybeAbsentField
	){
		public static OneOfEach random(Random r) {
			return new OneOfEach(
				r.nextBoolean()? null : "notNull_" + r.nextInt(1000),
				r.nextBoolean(),
				r.nextBoolean(),
				r.nextInt(10000),
				r.nextDouble() * 1000.0,
				"str_" + r.nextInt(1000),
				r.ints(nextExp(10, r), 0, 1000).mapToObj(i-> "str_" + i).toList(),
				Map.of(TimeUnit.SECONDS, BigDecimal.valueOf(r.nextDouble() * 1000.0)),
				Month.values()[r.nextInt(Month.values().length)],
				"computed_" + r.nextInt(1000),
				r.nextBoolean()? null : "maybeAbsent_" + r.nextInt(1000)
			);
		}

		private static int nextExp(int mean, Random r) {
			double lambda = 1.0 / mean;
			int limit = 10 * min(mean, Integer.MAX_VALUE/10);
			return min(limit, (int)(-Math.log(r.nextDouble()) / lambda));
		}

		public OneOfEach withComputedField(String computedField) {
			return new OneOfEach(
				nullField,
				trueField,
				falseField,
				integerField,
				realField,
				stringField,
				stringArrayField,
				mapField,
				monthField,
				computedField,
				maybeAbsentField);
		}

		public OneOfEach withMaybeAbsentField(String maybeAbsentField) {
			return new OneOfEach(
				nullField,
				trueField,
				falseField,
				integerField,
				realField,
				stringField,
				stringArrayField,
				mapField,
				monthField,
				computedField,
				maybeAbsentField);
		}
	}

	public enum Month {
		JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC;

		@JsonValue
		public int value() {
			return 1 + ordinal();
		}

		public static Month fromValue(int value) {
			// Note that Jackson does not use this; see javadocs for @JsonValue
			return values()[value-1];
		}

		public static RepresentAsSpec specNode() {
			return RepresentAsSpec.asInt(
				DataType.known(Month.class),
				Month::value,
				Month::fromValue
			);
		}

	}

	public record JustScalars(
		boolean trueField,
		boolean falseField,
		long integerField,
		double realField,
		String stringField
	){}

	public static ParseCallbackSpec testCallback(JsonValueSpec child) {
		long callbackNumber = callbackCounter.getAndIncrement();
		return new ParseCallbackSpec(
			TypedHandles.constant(DataType.LONG, callbackNumber),
			child,
			CallbackChecker.from(callbackNumber, child.dataType())
		);
	}

	record CallbackChecker(long expected) {
		void assertCorrect(long actual, Object parsed) {
			assertEquals(expected, actual);
		}

		static TypedHandle from(long expected, DataType parsedObjectType) {
			MethodType actualVirtualType = MethodType.methodType(void.class, long.class, Object.class);
			MethodType requiredMethodType = MethodType.methodType(void.class, CallbackChecker.class, long.class, parsedObjectType.leastUpperBoundClass());
			MethodHandle assertCorrect;
			try {
				assertCorrect = MethodHandles.lookup().findVirtual(CallbackChecker.class, "assertCorrect", actualVirtualType);
			} catch (NoSuchMethodException | IllegalAccessException e) {
				throw new AssertionError(e);
			}
			MethodHandle mh = explicitCastArguments(assertCorrect, requiredMethodType)
				.bindTo(new CallbackChecker(expected));
			return new TypedHandle(mh, DataType.VOID, List.of(DataType.LONG, parsedObjectType));
		}
	}

	private static final AtomicLong callbackCounter = new AtomicLong(123);

	static void main() throws IOException {
		Path targetDir = Path.of("boson/build/bigfiles");
		targetDir.toFile().mkdirs();
		writeRandomToFile(targetDir.resolve("1k.json"), 1_000);
		writeRandomToFile(targetDir.resolve("10k.json"), 10_000);
		writeRandomToFile(targetDir.resolve("100k.json"), 100_000);
	}

	public static void writeRandomToFile(Path file, int count) throws IOException {
		System.out.println("Writing " + count + " entries to " + file.toAbsolutePath());
		var r = new Random(123);
		var list = Stream
			.generate(()->OneOfEach.random(r))
			.limit(count)
			.toList();
		var jackson = new ObjectMapper().writerWithDefaultPrettyPrinter();
		try (var writer = new java.io.FileWriter(file.toFile(), UTF_8)) {
			jackson.writeValue(writer, list);
		}
	}

}
