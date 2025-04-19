package works.bosk.drivers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import works.bosk.Bosk;
import works.bosk.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.BoskTestUtils.boskName;

class ReplicaSetConformanceTest extends DriverConformanceTest {
	Bosk<TestEntity> replicaBosk;

	@BeforeEach
	void setupDriverFactory() {
		ReplicaSet<TestEntity> replicaSet = new ReplicaSet<>();
		replicaBosk = new Bosk<>(
			boskName("Replica"),
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			replicaSet.driverFactory(),
			Bosk.simpleRegistrar());
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
