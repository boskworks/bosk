package works.bosk.libtesting.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskContext;
import works.bosk.Identifier;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.SubmitConditionalReplacement;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OperationScriptCodecTest {
	private static final JsonMapper PLAIN_MAPPER = JsonMapper.builder().build();

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

	@Test
	void encodedShape() throws InvalidTypeException, IOException {
		Bosk<TestEntity> bosk = testBosk("shape");
		var catalogRef = bosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		BoskContext.Context context = BoskContext.Context.empty();
		Identifier id = Identifier.from("x");

		List<DriverOperation> operations = List.of(
			new SubmitReplacement<>(catalogRef.then(id).then(String.class, TestEntity.Fields.string), "value", context),
			new SubmitDeletion<>(catalogRef.then(id), context)
		);
		List<JsonNode> encoded = encodedOperations(operations, bosk);

		assertEquals(PLAIN_MAPPER.readTree("""
			{ "replacement": { "target": { "/catalog/x/string": "value" } } }
			"""), encoded.get(0));
		assertEquals(PLAIN_MAPPER.readTree("""
			{ "deletion": { "target": "/catalog/x" } }
			"""), encoded.get(1));
	}

	@Test
	void conditionalPreconditionCarriesRequiredValue() throws InvalidTypeException, IOException {
		Bosk<TestEntity> bosk = testBosk("conditional");
		var catalogRef = bosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		BoskContext.Context context = BoskContext.Context.empty();
		Identifier id = Identifier.from("x");
		var idRef = catalogRef.then(id).then(Identifier.class, TestEntity.Fields.id);

		List<DriverOperation> operations = List.of(
			new SubmitConditionalReplacement<>(catalogRef.then(id), TestEntity.empty(id, catalogRef), idRef, id, context)
		);
		JsonNode encoded = encodedOperations(operations, bosk).getFirst();

		JsonNode payload = encoded.get("conditionalReplacement");
		assertEquals(PLAIN_MAPPER.readTree("{ \"/catalog/x/id\": \"x\" }"), payload.get("precondition"));
		assertEquals(1, payload.get("target").properties().size());
	}

	@Test
	void valueTypeIsRecoveredFromTargetReference() throws InvalidTypeException, IOException {
		Bosk<TestEntity> bosk = testBosk("typing");
		var catalogRef = bosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		BoskContext.Context context = BoskContext.Context.empty();
		Identifier id = Identifier.from("x");

		List<DriverOperation> operations = List.of(
			new SubmitReplacement<>(catalogRef.then(id), TestEntity.empty(id, catalogRef), context),
			new SubmitReplacement<>(catalogRef.then(id).then(String.class, TestEntity.Fields.string), "value", context)
		);

		OperationScriptCodec codec = new OperationScriptCodec();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.write(List.of(codec.capture("typing", operations, bosk)), out);
		OperationScript reloaded = codec.read(new ByteArrayInputStream(out.toByteArray())).getFirst();

		List<DriverOperation> rebound = reloaded.operationsFor(bosk);
		SubmitReplacement<?> entityReplacement = assertInstanceOf(SubmitReplacement.class, rebound.get(0));
		SubmitReplacement<?> fieldReplacement = assertInstanceOf(SubmitReplacement.class, rebound.get(1));
		assertInstanceOf(TestEntity.class, entityReplacement.newValue());
		assertInstanceOf(String.class, fieldReplacement.newValue());
	}

	private static List<JsonNode> encodedOperations(List<DriverOperation> operations, Bosk<TestEntity> bosk) throws IOException {
		OperationScriptCodec codec = new OperationScriptCodec();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		codec.write(List.of(codec.capture("test", operations, bosk)), out);
		JsonNode root = PLAIN_MAPPER.readTree(new ByteArrayInputStream(out.toByteArray()));
		List<JsonNode> result = new ArrayList<>();
		root.get("scripts").get(0).get("operations").forEach(result::add);
		return result;
	}

	private static Bosk<TestEntity> testBosk(String name) {
		return new Bosk<>(
			name,
			TestEntity.class,
			b -> TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog)),
			BoskConfig.<TestEntity>builder().build());
	}
}
