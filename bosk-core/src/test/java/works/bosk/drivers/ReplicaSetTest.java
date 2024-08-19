package works.bosk.drivers;

import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.state.TestEntity;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.BoskTestUtils.boskName;

public class ReplicaSetTest extends AbstractDriverTest {
	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	@Test
	void joinAfterUpdate_correctInitialState() throws InvalidTypeException {
		var replicaSet = new ReplicaSet<TestEntity>();
		var bosk1 = new Bosk<TestEntity>(boskName("bosk1"), TestEntity.class, AbstractDriverTest::initialRoot, replicaSet.driverFactory());
		var refs1 = bosk1.rootReference().buildReferences(Refs.class);
		bosk1.driver().submitReplacement(refs1.string(), "New value");

		var bosk2 = new Bosk<TestEntity>(boskName("bosk2"), TestEntity.class, AbstractDriverTest::initialRoot, replicaSet.driverFactory());
		var refs2 = bosk2.rootReference().buildReferences(Refs.class);
		try (var _ = bosk2.readContext()) {
			assertEquals("New value", refs2.string().value());
		}
	}
}
