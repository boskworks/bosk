package works.bosk.drivers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.testing.drivers.SharedDriverConformanceTest;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

class ReplicaSetConformanceTest extends SharedDriverConformanceTest {
	Bosk<TestEntity> replicaBosk;

	@BeforeEach
	void setupDriverFactory() {
		ReplicaSet<TestEntity> replicaSet = new ReplicaSet<>();
		replicaBosk = new Bosk<>(
			boskName("Replica"),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(replicaSet.driverFactory())
				.build());
		driverFactory = replicaSet.driverFactory();
	}

	@AfterEach
	void checkFinalState() {
		TestEntity expected, actual;
		try (var _ = canonicalBosk.readSession()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (var _ = replicaBosk.readSession()) {
			actual = replicaBosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}
}
