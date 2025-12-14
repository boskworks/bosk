package works.bosk;

import java.util.LinkedHashMap;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import works.bosk.exceptions.InvalidTypeException;

import static org.openjdk.jmh.annotations.Mode.Throughput;
import static works.bosk.testing.BoskTestUtils.boskName;

@Fork(0)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CatalogBenchmark {

	@State(Scope.Benchmark)
	public static class BenchmarkState {
		private Catalog<AbstractBoskTest.TestEntity> catalog;
		private LinkedHashMap<Identifier, AbstractBoskTest.TestEntity> map;
		private AbstractBoskTest.TestEntity newEntity;

		@Setup(Level.Trial)
		public void setup() throws InvalidTypeException {
			Bosk<AbstractBoskTest.TestRoot> bosk = new Bosk<>(
				boskName(),
				AbstractBoskTest.TestRoot.class,
				AbstractBoskTest::initialRoot,
				BoskConfig.simple());
			TestEntityBuilder teb = new TestEntityBuilder(bosk);
			int initialSize = 100_000;
			catalog = Catalog.of(IntStream.rangeClosed(1, initialSize).mapToObj(i ->
				teb.blankEntity(Identifier.from("Entity_" + i), AbstractBoskTest.TestEnum.OK)));
			map = new LinkedHashMap<>();
			catalog.forEach(e -> map.put(e.id(), e));
			newEntity = teb.blankEntity(Identifier.from("New entity"), AbstractBoskTest.TestEnum.OK);
		}
	}

	@Benchmark
	@BenchmarkMode(Throughput)
	public Object catalogWith_sameEntity(BenchmarkState state) {
		return state.catalog.with(state.newEntity);
	}

	@Benchmark
	@BenchmarkMode(Throughput)
	public Object newLinkedHashMap_sameEntity(BenchmarkState state) {
		LinkedHashMap<Identifier, AbstractBoskTest.TestEntity> result = new LinkedHashMap<>(state.map);
		result.put(state.newEntity.id(), state.newEntity);
		return result;
	}

	@Benchmark
	@BenchmarkMode(Throughput)
	public Object linkedHashMapPut_sameEntity(BenchmarkState state) {
		return state.map.put(state.newEntity.id(), state.newEntity);
	}

}
