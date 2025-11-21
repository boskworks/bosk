package works.bosk.jackson;

import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.AbstractRoundTripTest;
import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.DriverStack;
import works.bosk.Reference;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static works.bosk.jackson.JacksonSerializerConfiguration.defaultConfiguration;

@Fork(0)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@OutputTimeUnit(MICROSECONDS)
public class JacksonRoundTripBenchmark extends AbstractRoundTripTest {

	@State(Scope.Benchmark)
	public static class BenchmarkState {
		private Bosk<TestRoot> bosk;
		private BoskDriver driver;
		private BoskDriver downstreamDriver;
		private Reference<TestRoot> rootRef;
		private TestRoot root1, root2;

		@Setup(Level.Trial)
		public void setup() throws JacksonException {
			AtomicReference<BoskDriver> downstreamRef = new AtomicReference<>();
			this.bosk = setUpBosk(DriverStack.of(
				jacksonRoundTripFactory(defaultConfiguration()),
				(_,d) -> {
					downstreamRef.set(d);
					return d;
				}
			));
			this.driver = bosk.driver();
			this.downstreamDriver = downstreamRef.get();
			JacksonSerializer jacksonSerializer = new JacksonSerializer();
			ObjectMapper mapper = JsonMapper.builder()
				.addModule(jacksonSerializer.moduleFor(bosk))
				.build();
			rootRef = bosk.rootReference();
			try (var _ = bosk.readContext()) {
				root1 = rootRef.value();
			}

			// Make a separate identical state object, cloning via JSON
			String json = mapper.writerFor(rootRef.targetClass()).writeValueAsString(root1);
			root2 = mapper.readerFor(rootRef.targetClass()).readValue(json);
		}

		@Setup(Level.Invocation)
		public void resetBoskState() {
			bosk.driver().submitReplacement(rootRef, root1);
		}
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public void replacementOverhead(BenchmarkState state) {
		state.downstreamDriver.submitReplacement(state.rootRef, state.root2);
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public void replacement(BenchmarkState state) {
		state.driver.submitReplacement(state.rootRef, state.root2);
	}

}
