package works.bosk.drivers;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.BoskTestUtils;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

public class ReplicaSetTest extends AbstractDriverTest {
	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	List<AutoCloseable> closeables;

	@BeforeEach
	void setup() {
		closeables = new ArrayList<>();
	}

	@AfterEach
	void teardown() throws Exception {
		for (var c : closeables.reversed()) {
			c.close();
		}
	}

	@Test
	void joinAfterUpdate_correctInitialState() throws InvalidTypeException {
		var replicaSet = new ReplicaSet<TestEntity>();
		var bosk1 = createBosk(BoskTestUtils.boskName("bosk1"), replicaSet);
		var refs1 = bosk1.rootReference().buildReferences(Refs.class);
		bosk1.driver().submitReplacement(refs1.string(), "New value");

		var bosk2 = createBosk(BoskTestUtils.boskName("bosk2"), replicaSet);
		var refs2 = bosk2.rootReference().buildReferences(Refs.class);
		try (var _ = bosk2.readSession()) {
			assertEquals("New value", refs2.string().value());
		}
	}

	@Test
	void secondaryConstructedInPrimaryReadSession_seesLatestState() throws InvalidTypeException {
		var replicaSet = new ReplicaSet<TestEntity>();
		var bosk1 = createBosk(boskName("bosk1"), replicaSet);
		var refs1 = bosk1.rootReference().buildReferences(Refs.class);

		Bosk<TestEntity> bosk2;
		try (var _ = bosk1.readSession()) {
			bosk1.driver().submitReplacement(refs1.string(), "New value");
			bosk2 = createBosk(boskName("bosk2"), replicaSet);
		}
		var refs2 = bosk2.rootReference().buildReferences(Refs.class);
		try (var _ = bosk2.readSession()) {
			assertEquals("New value", refs2.string().value());
		}
	}

	private @NonNull Bosk<TestEntity> createBosk(String name, ReplicaSet<TestEntity> replicaSet) {
		return new Bosk<>(name, TestEntity.class, this::initialState, BoskConfig.<TestEntity>builder().driverFactory(replicaSet.driverFactory()).build());
	}
}
