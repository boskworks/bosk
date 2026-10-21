package works.bosk.libtesting.fuzz;

import java.util.List;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskContext;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.fuzz.AbstractFuzzRegressionTest;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

import static works.bosk.BoskConfig.simpleDriver;

/**
 * Makes sure {@link AbstractFuzzRegressionTest} works properly by replaying a script against a
 * known-good driver.
 */
class SimpleDriverFuzzRegressionTest extends AbstractFuzzRegressionTest {

	@Override
	protected DriverFactory<TestEntity> driverFactory() {
		return simpleDriver();
	}

	@Override
	protected List<OperationScript> scripts() throws InvalidTypeException {
		Bosk<TestEntity> source = new Bosk<>(
			"source",
			TestEntity.class,
			b -> TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog)),
			BoskConfig.<TestEntity>builder().build());
		var catalogRef = source.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		BoskContext.Context context = BoskContext.Context.empty();
		Identifier id = Identifier.from("x");
		return List.of(new OperationScriptCodec().capture("create-replace-delete", List.of(
			new ConditionalCreation<>(catalogRef.then(id), TestEntity.empty(id, catalogRef).withString("hi"), context),
			new SubmitReplacement<>(catalogRef.then(id).then(String.class, TestEntity.Fields.string), "bye", context),
			new SubmitDeletion<>(catalogRef.then(id), context)
		), source));
	}
}
