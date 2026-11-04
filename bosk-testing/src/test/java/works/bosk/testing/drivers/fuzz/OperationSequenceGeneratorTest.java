package works.bosk.testing.drivers.fuzz;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.operations.UpdateOperation;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OperationSequenceGeneratorTest {
	private Bosk<TestEntity> bosk;

	@BeforeEach
	void setup() {
		bosk = new Bosk<>(
			"test",
			TestEntity.class,
			b -> TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog)),
			BoskConfig.<TestEntity>builder().build());
	}

	@Test
	void emptyCatalog_yieldsCreation() throws InvalidTypeException {
		OperationSequenceGenerator generator = generatorForcedTo(OperationKind.CREATE);
		UpdateOperation operation = assertInstanceOf(UpdateOperation.class, generator.nextOperation());
		assertEquals(Path.of(TestEntity.Fields.catalog, "entity0"), operation.target().path());
	}

	@Test
	void populatedCatalog_yieldsUpdateToExistingEntry() throws InvalidTypeException, IOException, InterruptedException {
		createFirstEntity();
		OperationSequenceGenerator generator = generatorForcedTo(OperationKind.DELETE);
		UpdateOperation operation = assertInstanceOf(UpdateOperation.class, generator.nextOperation());
		assertEquals(Path.of(TestEntity.Fields.catalog, "entity0"), operation.target().path());
	}

	@Test
	void sameSeed_yieldsSameOperation() throws InvalidTypeException {
		OperationSequenceGenerator first = generator(OperationChooser.random());
		OperationSequenceGenerator second = generator(OperationChooser.random());
		assertEquals(first.nextOperation().toString(), second.nextOperation().toString());
	}

	private void createFirstEntity() throws InvalidTypeException, IOException, InterruptedException {
		UpdateOperation creation = assertInstanceOf(UpdateOperation.class, generatorForcedTo(OperationKind.CREATE).nextOperation());
		creation.submitTo(bosk.driver());
		bosk.driver().flush();
	}

	private OperationSequenceGenerator generatorForcedTo(OperationKind kind) {
		return generator((random, applicable) -> kind);
	}

	private OperationSequenceGenerator generator(OperationChooser chooser) {
		return new OperationSequenceGenerator(new Random(42), bosk, bosk, chooser);
	}
}
