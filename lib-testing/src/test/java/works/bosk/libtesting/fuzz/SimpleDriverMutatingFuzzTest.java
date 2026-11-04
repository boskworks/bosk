package works.bosk.libtesting.fuzz;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.fuzz.OperationChooser;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.fuzz.OperationSequenceGenerator;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.state.TestEntity;

import static works.bosk.BoskConfig.simpleDriver;

/**
 * Makes sure {@link AbstractMutatingFuzzTest} works properly by mutating scripts generated against
 * a known-good in-memory bosk.
 */
class SimpleDriverMutatingFuzzTest extends AbstractMutatingFuzzTest {

	@Override
	protected DriverFactory<TestEntity> driverFactory() {
		return simpleDriver();
	}

	@Override
	protected List<OperationScript> seedCorpus() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> source = new Bosk<>(
			"source",
			TestEntity.class,
			b -> TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog)),
			BoskConfig.<TestEntity>builder().build());
		OperationScriptCodec codec = new OperationScriptCodec();
		List<OperationScript> corpus = new ArrayList<>();
		for (long seed = 1; seed <= 3; seed++) {
			OperationSequenceGenerator generator = new OperationSequenceGenerator(new Random(seed), source, source, OperationChooser.random());
			List<DriverOperation> operations = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				DriverOperation operation = generator.nextOperation();
				operations.add(operation);
				operation.submitTo(source.driver());
				source.driver().flush();
			}
			corpus.add(codec.capture("seed-" + seed, operations, source));
		}
		return corpus;
	}
}
