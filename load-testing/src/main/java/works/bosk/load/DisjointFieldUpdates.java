package works.bosk.load;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * A conflict-free workload: each worker owns a disjoint slice of the root catalog and repeatedly
 * replaces the {@code string} field of those entities.
 *
 * <p>Because no two workers write the same entity, the expected final state is deterministic
 * regardless of scheduling. Each worker remembers the last value it wrote for each of its
 * entities, and {@link #verify} rebuilds the expected catalog from those values.
 */
public final class DisjointFieldUpdates implements Workload {
	private final int entityCount;
	private final int threads;
	private final int valueBytes;
	private final Identifier[] ids;
	private final String[] lastValues;
	private List<List<Reference<String>>> targets;
	private int[][] globalIndices;
	private int[] cursors;

	public DisjointFieldUpdates(int entityCount, int threads, int valueBytes) {
		this.entityCount = entityCount;
		this.threads = threads;
		this.valueBytes = valueBytes;
		this.ids = new Identifier[entityCount];
		this.lastValues = new String[entityCount];
		for (int i = 0; i < entityCount; i++) {
			ids[i] = Identifier.from("entity" + i);
			lastValues[i] = initialValue(i);
		}
	}

	@Override
	public TestEntity initialRoot(Bosk<TestEntity> bosk) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = catalogRef(bosk);
		Catalog<TestEntity> catalog = Catalog.of(IntStream.range(0, entityCount)
			.mapToObj(i -> TestEntity.empty(ids[i], catalogRef).withString(lastValues[i])));
		return TestEntity.empty(ROOT_ID, catalogRef).withCatalog(catalog);
	}

	@Override
	public void initialize(Bosk<TestEntity> bosk) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = catalogRef(bosk);
		List<List<Reference<String>>> threadTargets = new ArrayList<>(threads);
		int[][] indices = new int[threads][];
		for (int thread = 0; thread < threads; thread++) {
			int start = (int) ((long) entityCount * thread / threads);
			int end = (int) ((long) entityCount * (thread + 1) / threads);
			List<Reference<String>> targetsForThread = new ArrayList<>(end - start);
			int[] indicesForThread = new int[end - start];
			for (int i = start; i < end; i++) {
				targetsForThread.add(catalogRef.then(ids[i]).then(String.class, TestEntity.Fields.string));
				indicesForThread[i - start] = i;
			}
			threadTargets.add(List.copyOf(targetsForThread));
			indices[thread] = indicesForThread;
		}
		this.targets = List.copyOf(threadTargets);
		this.globalIndices = indices;
		this.cursors = new int[threads];
	}

	@Override
	public void submit(BoskDriver driver, int threadIndex, long iteration) {
		List<Reference<String>> threadTargets = targets.get(threadIndex);
		if (!threadTargets.isEmpty()) {
			int cursor = cursors[threadIndex];
			String value = updateValue(threadIndex, iteration);
			driver.submitReplacement(threadTargets.get(cursor), value);
			lastValues[globalIndices[threadIndex][cursor]] = value;
			cursors[threadIndex] = (cursor + 1) % threadTargets.size();
		}
	}

	@Override
	public Verification verify(Bosk<TestEntity> bosk) {
		try (var _ = bosk.readSession()) {
			CatalogReference<TestEntity> catalogRef = catalogRef(bosk);
			int failed = 0;
			String firstFailure = null;
			for (int i = 0; i < entityCount; i++) {
				String actual;
				try {
					Reference<String> target = catalogRef.then(ids[i]).then(String.class, TestEntity.Fields.string);
					actual = target.value();
				} catch (RuntimeException e) {
					actual = "<" + e + ">";
				}
				if (!lastValues[i].equals(actual)) {
					failed++;
					if (firstFailure == null) {
						firstFailure = ids[i] + ": expected \"" + lastValues[i] + "\" but was \"" + actual + "\"";
					}
				}
			}
			return failed == 0
				? Verification.matched(entityCount)
				: Verification.failed(entityCount, failed, firstFailure);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Unable to build the expected state", e);
		}
	}

	private static CatalogReference<TestEntity> catalogRef(Bosk<TestEntity> bosk) throws InvalidTypeException {
		return bosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
	}

	private String initialValue(int entityIndex) {
		return padded("init-" + entityIndex);
	}

	private String updateValue(int threadIndex, long iteration) {
		return padded((threadIndex + 1) + "-" + iteration);
	}

	private String padded(String value) {
		if (value.length() >= valueBytes) {
			return value.substring(0, valueBytes);
		} else {
			return value + "x".repeat(valueBytes - value.length());
		}
	}

	private static final Identifier ROOT_ID = Identifier.from("root");
}
