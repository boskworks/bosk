package works.bosk.libtesting.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskContext;
import works.bosk.Identifier;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationScriptCodecTest {

	@Test
	void capturedScriptReplaysAgainstAnotherBosk() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> source = testBosk("source");
		var catalogRef = source.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		BoskContext.Context context = BoskContext.Context.empty();
		Identifier id = Identifier.from("x");
		List<DriverOperation> operations = List.of(
			new ConditionalCreation<>(catalogRef.then(id), TestEntity.empty(id, catalogRef).withString("hi"), context),
			new SubmitReplacement<>(catalogRef.then(id).then(String.class, TestEntity.Fields.string), "bye", context)
		);

		OperationScriptCodec codec = new OperationScriptCodec();
		OperationScript script = codec.capture("create-and-replace", operations, source);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.write(List.of(script), out);
		List<OperationScript> reloaded = codec.read(new ByteArrayInputStream(out.toByteArray()));
		assertEquals(1, reloaded.size());

		// Rebuild against a different bosk and confirm the operations still have the intended effect.
		Bosk<TestEntity> replay = testBosk("replay");
		List<DriverOperation> rebound = reloaded.getFirst().operationsFor(replay);
		assertEquals(2, rebound.size());
		for (DriverOperation operation : rebound) {
			operation.submitTo(replay.driver());
			replay.driver().flush();
		}
		try (var _ = replay.readSession()) {
			assertEquals("bye", replay.rootReference().value().catalog().get(id).string());
		}
	}

	private static Bosk<TestEntity> testBosk(String name) {
		return new Bosk<>(
			name,
			TestEntity.class,
			b -> TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog)),
			BoskConfig.<TestEntity>builder().build());
	}
}
