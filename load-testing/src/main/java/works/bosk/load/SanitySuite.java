package works.bosk.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the performance sanity suite: a fixed set of short scenarios, each in its
 * own JVM, with the numbers written to a CSV and the environment written
 * alongside it.
 *
 * <p>This is not a benchmark: it exists to notice when something got markedly
 * slower. The numbers are trustworthy enough to compare two runs on the same
 * machine, and the environment file says whether they are comparable at all.
 *
 * <p>Usage:
 * <pre>
 * ./gradlew :load-testing:sanity
 * ./gradlew :load-testing:sanity --args="--label=my-branch --reps=3"
 * ./gradlew :load-testing:sanity --args="--only=update-pando-8,flush-per-update"
 * ./gradlew :load-testing:sanity --args="--list"
 * </pre>
 */
public final class SanitySuite {

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		if (options.help) {
			Options.printUsage();
			return;
		}
		if (options.list) {
			for (SanityScenario scenario : SanityScenario.ALL) {
				System.out.printf("%-20s %-6s %s%n", scenario.name(), scenario.group(), scenario.description());
			}
			return;
		}

		List<SanityScenario> scenarios = select(options.only);
		Files.createDirectories(options.out);
		EnvironmentInfo environment = EnvironmentInfo.capture();
		String gitSha = git("rev-parse", "--short=12", "HEAD");
		boolean gitDirty = !git("status", "--porcelain", "--untracked-files=no").isBlank();
		Instant startedAt = Instant.now();

		System.out.printf("Sanity suite %s at %s (%s%s), %d scenario(s), %d rep(s)%n",
			options.label, startedAt, gitSha, gitDirty ? ", dirty" : "",
			scenarios.size(), options.reps);

		List<Run> runs = new ArrayList<>();
		for (SanityScenario scenario : scenarios) {
			for (int rep = 1; rep <= options.reps; rep++) {
				String base = scenario.name() + "-rep" + rep;
				Path metrics = options.out.resolve(base + ".metrics");
				Path log = options.out.resolve(base + ".log");
				runChild(scenario, metrics, log, options.out.resolve(base + ".work"));
				runs.add(parse(scenario, metrics));
				System.out.printf("  %s rep %d: done%n", scenario.name(), rep);
			}
		}

		List<Summary> summaries = summarise(runs, scenarios);
		printTable(summaries);
		writeCsv(options.out.resolve("sanity-" + options.label + ".csv"), summaries);
		writeEnvironment(options.out.resolve("sanity-" + options.label + ".env"),
			options, environment, gitSha, gitDirty, startedAt);
		System.out.println();
		System.out.println("Results written to " + options.out.toAbsolutePath());
	}

	private static List<SanityScenario> select(List<String> names) {
		if (names.isEmpty()) {
			return SanityScenario.ALL;
		}
		List<SanityScenario> selected = new ArrayList<>(names.size());
		for (String name : names) {
			selected.add(SanityScenario.byName(name)
				.orElseThrow(() -> new IllegalArgumentException("Unknown scenario \"" + name + "\"")));
		}
		return selected;
	}

	private static void runChild(SanityScenario scenario, Path metrics, Path log, Path work) throws IOException, InterruptedException {
		Files.createDirectories(work);
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> command = List.of(
			java,
			"-cp", System.getProperty("java.class.path"),
			SanityChild.class.getName(),
			scenario.name(),
			metrics.toString(),
			work.toString());
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.redirectOutput(log.toFile())
			.start();
		int exit = process.waitFor();
		if (exit != 0) {
			System.err.println("Scenario " + scenario.name() + " failed with exit code " + exit);
			System.err.println("See " + log + "; last lines:");
			for (String line : tail(log, 30)) {
				System.err.println("  " + line);
			}
			System.exit(1);
		}
	}

	private static Run parse(SanityScenario scenario, Path metrics) throws IOException {
		Map<String, Value> values = new LinkedHashMap<>();
		for (String line : Files.readAllLines(metrics, StandardCharsets.UTF_8)) {
			if (line.startsWith("metric\t")) {
				String[] parts = line.split("\t");
				values.put(parts[1], new Value(Double.parseDouble(parts[2]), parts[3]));
			}
		}
		return new Run(scenario, values);
	}

	private static List<Summary> summarise(List<Run> runs, List<SanityScenario> scenarios) {
		Map<String, Summary> byKey = new LinkedHashMap<>();
		for (SanityScenario scenario : scenarios) {
			for (Run run : runs) {
				if (run.scenario.name().equals(scenario.name())) {
					for (Map.Entry<String, Value> entry : run.values.entrySet()) {
						String key = scenario.name() + '\t' + entry.getKey();
						byKey.computeIfAbsent(key, _ -> new Summary(scenario, entry.getKey(),
								entry.getValue().unit(), new ArrayList<>()))
							.values.add(entry.getValue().value());
					}
				}
			}
		}
		return new ArrayList<>(byKey.values());
	}

	private static void printTable(List<Summary> summaries) {
		System.out.println();
		System.out.printf("%-20s %-22s %-8s %12s %20s%n", "scenario", "metric", "unit", "median", "min..max");
		System.out.println("-".repeat(84));
		for (Summary summary : summaries) {
			System.out.printf("%-20s %-22s %-8s %12.3f %20s%n",
				summary.scenario.name(),
				summary.metric,
				summary.unit,
				summary.median(),
				String.format(Locale.ROOT, "%.3f..%.3f", summary.min(), summary.max()));
		}
	}

	private static void writeCsv(Path file, List<Summary> summaries) throws IOException {
		StringBuilder csv = new StringBuilder("scenario,group,metric,unit");
		for (int i = 0; i < maxReps(summaries); i++) {
			csv.append(",rep").append(i + 1);
		}
		csv.append(",median,min,max\n");
		for (Summary summary : summaries) {
			csv.append(summary.scenario.name()).append(',')
				.append(summary.scenario.group()).append(',')
				.append(summary.metric).append(',')
				.append(summary.unit);
			for (double value : summary.values) {
				csv.append(String.format(Locale.ROOT, ",%.6f", value));
			}
			csv.append(String.format(Locale.ROOT, ",%.6f,%.6f,%.6f%n",
				summary.median(), summary.min(), summary.max()));
		}
		Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
	}

	private static void writeEnvironment(Path file, Options options, EnvironmentInfo environment,
		String gitSha, boolean gitDirty, Instant startedAt) throws IOException {
		StringBuilder text = new StringBuilder();
		text.append("label=").append(options.label).append('\n');
		text.append("startedAt=").append(startedAt).append('\n');
		text.append("gitSha=").append(gitSha).append('\n');
		text.append("gitDirty=").append(gitDirty).append('\n');
		text.append("reps=").append(options.reps).append('\n');
		text.append("javaVersion=").append(environment.javaVersion()).append('\n');
		text.append("javaVendor=").append(environment.javaVendor()).append('\n');
		text.append("vmName=").append(environment.vmName()).append('\n');
		text.append("maxHeapBytes=").append(environment.maxMemoryBytes()).append('\n');
		text.append("availableProcessors=").append(environment.availableProcessors()).append('\n');
		text.append("performanceCores=").append(environment.performanceCores()).append('\n');
		text.append("efficiencyCores=").append(environment.efficiencyCores()).append('\n');
		text.append("cpuModel=").append(environment.cpuModel()).append('\n');
		text.append("osName=").append(environment.osName()).append('\n');
		text.append("osArch=").append(environment.osArch()).append('\n');
		text.append("mongoImage=").append(MongoEnvironment.MONGO_IMAGE).append('\n');
		text.append("jvmArguments=").append(String.join(" ", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())).append('\n');
		Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
	}

	private static int maxReps(List<Summary> summaries) {
		return summaries.stream().mapToInt(s -> s.values.size()).max().orElse(0);
	}

	private static List<String> tail(Path file, int lines) {
		try {
			List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
			return all.subList(Math.max(0, all.size() - lines), all.size());
		} catch (IOException e) {
			return List.of("(unable to read " + file + ": " + e.getMessage() + ")");
		}
	}

	private static String git(String... args) {
		try {
			List<String> command = new ArrayList<>();
			command.add("git");
			command.addAll(List.of(args));
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			return process.waitFor() == 0 ? output : "";
		} catch (IOException e) {
			return "";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		}
	}

	record Value(double value, String unit) {
	}

	record Run(SanityScenario scenario, Map<String, Value> values) {
	}

	private static final class Summary {
		final SanityScenario scenario;
		final String metric;
		final String unit;
		final List<Double> values;

		Summary(SanityScenario scenario, String metric, String unit, List<Double> values) {
			this.scenario = scenario;
			this.metric = metric;
			this.unit = unit;
			this.values = values;
		}

		double median() {
			List<Double> sorted = values.stream().sorted().toList();
			int middle = sorted.size() / 2;
			if (sorted.size() % 2 == 1) {
				return sorted.get(middle);
			} else {
				return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
			}
		}

		double min() {
			return values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		}

		double max() {
			return values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
		}
	}

	private record Options(String label, int reps, Path out, List<String> only, boolean list, boolean help) {

		static Options parse(String[] args) {
			String label = "local";
			int reps = 2;
			Path out = Path.of("build", "sanity-results");
			List<String> only = new ArrayList<>();
			boolean list = false;
			boolean help = false;
			for (String arg : args) {
				if (arg.equals("--list")) {
					list = true;
				} else if (arg.equals("--help") || arg.equals("-h")) {
					help = true;
				} else if (arg.startsWith("--")) {
					int equals = arg.indexOf('=');
					if (equals < 0) {
						throw new IllegalArgumentException("Option \"" + arg + "\" must have the form --name=value");
					}
					String name = arg.substring(2, equals);
					String value = arg.substring(equals + 1);
					switch (name) {
						case "label" -> label = value;
						case "reps" -> reps = Integer.parseInt(value);
						case "out" -> out = Path.of(value);
						case "only" -> {
							for (String scenario : value.split(",")) {
								if (!scenario.isBlank()) {
									only.add(scenario.trim());
								}
							}
						}
						default -> throw new IllegalArgumentException("Unknown option --" + name);
					}
				} else {
					throw new IllegalArgumentException("Unexpected argument \"" + arg + "\"");
				}
			}
			return new Options(label, reps, out, only, list, help);
		}

		static void printUsage() {
			System.out.println("""
				Runs the performance sanity suite.

				Options:
				--label=<name>     name for the output files (default: local)
				--reps=<n>         repetitions per scenario (default: 2)
				--out=<dir>        output directory (default: build/sanity-results)
				--only=<a,b,...>   run only the named scenarios
				--list             list the scenarios and exit
				--help             show this message
				""");
		}
	}
}
