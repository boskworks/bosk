package works.bosk.load;

import java.util.List;
import java.util.Optional;

/**
 * One entry in the performance sanity suite: a workload described once, so that
 * every run measures the same thing.
 *
 * <p>A scenario is either a {@link LoadConfig} expressed as the command-line
 * arguments {@link Main} accepts, or a phase that {@link SanityChild} implements
 * directly, for things the load harness doesn't measure, such as how long a bosk
 * takes to initialize.
 *
 * <p>Durations are deliberately short: the suite is meant to be run often enough
 * that a marked slowdown is noticed, not to produce precise throughput numbers.
 */
public record SanityScenario(String name, String group, String description, List<String> loadArgs) {

	/**
	 * True for scenarios the load harness runs, false for phases.
	 */
	public boolean isLoad() {
		return loadArgs != null;
	}

	static SanityScenario load(String name, String group, String description, String... args) {
		return new SanityScenario(name, group, description, List.of(args));
	}

	static SanityScenario phase(String name, String group, String description) {
		return new SanityScenario(name, group, description, null);
	}

	static Optional<SanityScenario> byName(String name) {
		return ALL.stream().filter(s -> s.name().equals(name)).findFirst();
	}

	/**
	 * The scenario list, in the order the suite runs them.
	 *
	 * <p>The order is fixed so that runs of different revisions are comparable
	 * even though each scenario runs in its own JVM.
	 */
	public static final List<SanityScenario> ALL = List.of(
		load("update-pando-1", "mongo",
			"One thread of disjoint field updates through the grafted Pando driver.",
			"--driver=mongo-pando-grafted", "--threads=1", "--entities=8",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("update-pando-8", "mongo",
			"Eight threads of disjoint field updates through the grafted Pando driver.",
			"--driver=mongo-pando-grafted", "--threads=8", "--entities=64",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("update-sequoia-8", "mongo",
			"Eight threads of disjoint field updates through the Sequoia driver.",
			"--driver=mongo-sequoia", "--threads=8", "--entities=64",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("flush-per-update", "mongo",
			"One thread flushing after every update: the flush path a request would take.",
			"--driver=mongo-pando-grafted", "--threads=1", "--entities=8",
			"--warmup=10s", "--duration=10s", "--flush=everyOp", "--valueBytes=64"),
		load("submit-simple-1", "core",
			"One thread submitting through the simple in-memory driver: the core submit path with no hooks.",
			"--driver=simple", "--threads=1", "--entities=8",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("submit-simple-8", "core",
			"Eight threads submitting through the simple in-memory driver: the core submit path with no I/O.",
			"--driver=simple", "--threads=8", "--entities=64",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("hooks-root-1", "core",
			"One thread submitting with a single root hook that does nothing: the per-update hook dispatch cost.",
			"--driver=simple", "--threads=1", "--entities=8", "--hookCount=1", "--hookScope=root",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("hooks-root-8", "core",
			"Eight threads submitting with a single root hook: hook dispatch under load.",
			"--driver=simple", "--threads=8", "--entities=64", "--hookCount=1", "--hookScope=root",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("hooks-root-2", "core",
			"Two threads submitting with a single root hook: whether hook execution keeps up with the updates.",
			"--driver=simple", "--threads=2", "--entities=16", "--hookCount=1", "--hookScope=root",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("hooks-root-4", "core",
			"Four threads submitting with a single root hook.",
			"--driver=simple", "--threads=4", "--entities=32", "--hookCount=1", "--hookScope=root",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("hooks-entity-8", "core",
			"Eight threads submitting with one hook scoped to every entity's string field.",
			"--driver=simple", "--threads=8", "--entities=64", "--hookCount=1", "--hookScope=entity",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("read-simple-8", "core",
			"Eight reader threads reading through the simple driver, one read session per operation.",
			"--driver=simple", "--threads=1", "--entities=8", "--readers=8",
			"--readShape=leaf", "--sessionLifetime=perOp",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		load("read-simple-perthread", "core",
			"Eight reader threads reading through the simple driver, each holding one read session for the run.",
			"--driver=simple", "--threads=1", "--entities=8", "--readers=8",
			"--readShape=leaf", "--sessionLifetime=perThread",
			"--warmup=10s", "--duration=10s", "--flush=end", "--valueBytes=64"),
		phase("flush-idle", "mongo",
			"Flush with no writes pending: the epoch check and probe cost on a quiet stream."),
		phase("flush-after-burst", "mongo",
			"One flush after a burst of a hundred updates: the backlog probe path."),
		phase("init-1000", "mongo",
			"Time to construct a bosk with a thousand-entry catalog.")
	);
}
