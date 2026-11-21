package works.bosk.load;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the command-line options accepted by {@link Main}.
 *
 * <p>Options are of the form {@code --name=value}. Thread counts may be absolute, or relative
 * to the machine's available processors: {@code P}, {@code P/2}, {@code 2P}, and {@code 2*P}
 * are all accepted.
 */
public final class LoadConfigParser {

	private LoadConfigParser() {
	}

	public static LoadConfig parse(String[] args) {
		LoadConfig.Builder builder = LoadConfig.builder();
		for (String arg : args) {
			if (!arg.startsWith("--")) {
				throw new IllegalArgumentException("Unexpected argument \"" + arg + "\"; options must start with --");
			}
			int equals = arg.indexOf('=');
			if (equals < 0) {
				throw new IllegalArgumentException("Option \"" + arg + "\" must have the form --name=value");
			}
			String name = arg.substring(2, equals);
			String value = arg.substring(equals + 1);
			switch (name) {
				case "label" -> builder.label(value);
				case "driver" -> builder.driver(DriverKind.fromCli(value));
				case "entities" -> builder.entityCount(intValue(name, value));
				case "threads" -> builder.threads(parseThreads(value, Runtime.getRuntime().availableProcessors()));
				case "warmup" -> builder.warmup(parseDuration(name, value));
				case "duration" -> builder.duration(parseDuration(name, value));
				case "flush" -> builder.flushMode(parseFlushMode(value));
				case "flushEvery" -> builder.flushEvery(intValue(name, value));
				case "valueBytes" -> builder.valueBytes(intValue(name, value));
				case "hookLatency" -> builder.hookLatency(parseDuration(name, value));
				case "hookScope" -> builder.hookScope(HookScope.fromCli(value));
				case "hookCount" -> builder.hookCount(intValue(name, value));
				case "hookQueueMaxSize" -> builder.hookQueueMaxSize(intValue(name, value));
				case "seed" -> builder.seed(longValue(name, value));
				case "stallTimeout" -> builder.stallTimeout(parseDuration(name, value));
				case "out" -> builder.outputDirectory(Path.of(value));
				default -> throw new IllegalArgumentException("Unknown option --" + name);
			}
		}
		return builder.build();
	}

	static int parseThreads(String value, int processors) {
		String trimmed = value.trim();
		if (trimmed.equalsIgnoreCase("P")) {
			return processors;
		}
		Matcher half = HALF_PROCESSORS.matcher(trimmed);
		if (half.matches()) {
			int divisor = Integer.parseInt(half.group(1));
			return Math.max(1, (processors + divisor - 1) / divisor);
		}
		Matcher multiple = MULTIPLE_PROCESSORS.matcher(trimmed);
		if (multiple.matches()) {
			int multiplier = Integer.parseInt(multiple.group(1));
			return Math.max(1, Math.multiplyExact(processors, multiplier));
		}
		try {
			return Math.max(1, Integer.parseInt(trimmed));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid thread count \"" + value + "\"", e);
		}
	}

	static Duration parseDuration(String name, String value) {
		Matcher matcher = DURATION.matcher(value.trim());
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid duration for --" + name + ": \"" + value + "\"");
		}
		long amount = Long.parseLong(matcher.group(1));
		return switch (matcher.group(2)) {
			case "ms" -> Duration.ofMillis(amount);
			case "s" -> Duration.ofSeconds(amount);
			case "m" -> Duration.ofMinutes(amount);
			case "h" -> Duration.ofHours(amount);
			default -> throw new AssertionError("Unexpected duration unit " + matcher.group(2));
		};
	}

	static FlushMode parseFlushMode(String value) {
		return switch (value.toLowerCase(Locale.ROOT).replace("_", "")) {
			case "everyop" -> FlushMode.EVERY_OP;
			case "everyn" -> FlushMode.EVERY_N;
			case "end", "endonly" -> FlushMode.END_ONLY;
			default -> throw new IllegalArgumentException("Invalid flush mode \"" + value + "\"; expected everyOp, everyN, or end");
		};
	}

	private static int intValue(String name, String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid integer for --" + name + ": \"" + value + "\"", e);
		}
	}

	private static long longValue(String name, String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid integer for --" + name + ": \"" + value + "\"", e);
		}
	}


	private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");
	private static final Pattern HALF_PROCESSORS = Pattern.compile("[Pp]/(\\d+)");
	private static final Pattern MULTIPLE_PROCESSORS = Pattern.compile("(\\d+)\\*?[Pp]");
}
