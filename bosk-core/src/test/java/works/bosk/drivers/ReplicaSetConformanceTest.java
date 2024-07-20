package works.bosk.drivers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import works.bosk.Bosk;
import works.bosk.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplicaSetConformanceTest extends DriverConformanceTest {
	Bosk<TestEntity> replicaBosk;

	@BeforeEach
	void setupDriverFactory() {
		ReplicaSet<TestEntity> replicaSet = new ReplicaSet<>();
		replicaBosk = new Bosk<TestEntity>(
			"Replica bosk",
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			replicaSet.driverFactory());
		driverFactory = replicaSet.driverFactory();
	}

	@AfterEach
	void checkFinalState() {
		TestEntity expected, actual;
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = canonicalBosk.readContext()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = replicaBosk.readContext()) {
			actual = replicaBosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}
}