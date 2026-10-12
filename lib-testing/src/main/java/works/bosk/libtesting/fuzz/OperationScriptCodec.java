package works.bosk.libtesting.fuzz;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.Reference;
import works.bosk.testing.drivers.fuzz.OperationKind;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.FlushOperation;
import works.bosk.testing.drivers.operations.SubmitConditionalDeletion;
import works.bosk.testing.drivers.operations.SubmitConditionalReplacement;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * Reads and writes {@link OperationScript}s as JSON.
 *
 * <p>{@link #capture} turns operations that were generated against some bosk into a portable
 * script. Operation values are serialized with the state-tree serializer (here, Jackson) so they
 * use the same representation as the bosk state itself; on replay, {@link SpecOperationScript}
 * rebuilds them against the bosk under test.
 *
 * <p>The value type of each operation is implied by its {@link OperationKind}: a whole-entity
 * replacement carries a {@link TestEntity}, a field replacement carries a {@link String}, and so
 * on. This keeps the stored form free of type names, at the cost of being specific to the
 * {@code TestEntity} conformance schema.
 */
public final class OperationScriptCodec {
	private final ObjectMapper mapper = JsonMapper.builder().build();

	/**
	 * @return a portable script equivalent to {@code operations}, whose references were bound to {@code bosk}
	 */
	public OperationScript capture(String name, List<DriverOperation> operations, Bosk<TestEntity> bosk) {
		ObjectMapper valueMapper = SpecOperationScript.mapperFor(bosk);
		List<OperationSpec> specs = new ArrayList<>(operations.size());
		for (DriverOperation operation : operations) {
			specs.add(specOf(operation, valueMapper));
		}
		return new SpecOperationScript(name, List.copyOf(specs));
	}

	public void write(Collection<OperationScript> scripts, OutputStream out) {
		List<SpecOperationScript> specScripts = new ArrayList<>(scripts.size());
		for (OperationScript script : scripts) {
			if (script instanceof SpecOperationScript specScript) {
				specScripts.add(specScript);
			} else {
				throw new IllegalArgumentException("Cannot write script of type " + script.getClass());
			}
		}
		mapper.writeValue(out, new ScriptFile(specScripts));
	}

	public List<OperationScript> read(InputStream in) {
		ScriptFile file = mapper.readValue(in, ScriptFile.class);
		return List.copyOf(file.scripts());
	}

	private static OperationSpec specOf(DriverOperation operation, ObjectMapper valueMapper) {
		if (operation instanceof ConditionalCreation<?> op) {
			return new OperationSpec(OperationKind.CREATE, path(op.target()), null, null, valueMapper.valueToTree(op.newValue()));
		} else if (operation instanceof SubmitReplacement<?> op) {
			OperationKind kind = op.newValue() instanceof String ? OperationKind.REPLACE_FIELD : OperationKind.REPLACE_ENTITY;
			return new OperationSpec(kind, path(op.target()), null, null, valueMapper.valueToTree(op.newValue()));
		} else if (operation instanceof SubmitDeletion<?> op) {
			return new OperationSpec(OperationKind.DELETE, path(op.target()), null, null, null);
		} else if (operation instanceof SubmitConditionalReplacement<?> op) {
			return new OperationSpec(OperationKind.CONDITIONAL_REPLACE, path(op.target()), path(op.precondition()), op.requiredValue().toString(), valueMapper.valueToTree(op.newValue()));
		} else if (operation instanceof SubmitConditionalDeletion<?> op) {
			return new OperationSpec(OperationKind.CONDITIONAL_DELETE, path(op.target()), path(op.precondition()), op.requiredValue().toString(), null);
		} else if (operation instanceof FlushOperation) {
			return new OperationSpec(OperationKind.FLUSH, null, null, null, null);
		} else {
			throw new IllegalArgumentException("Cannot capture operation " + operation);
		}
	}

	private static String path(Reference<?> reference) {
		return reference.path().urlEncoded();
	}

	record ScriptFile(List<SpecOperationScript> scripts) {}
}
