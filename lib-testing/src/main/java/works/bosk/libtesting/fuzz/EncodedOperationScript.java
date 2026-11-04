package works.bosk.libtesting.fuzz;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.bosk.Bosk;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * An {@link OperationScript} stored as JSON operation nodes.
 *
 * <p>The nodes are bosk-independent: they contain paths and serialized values, but no
 * {@code Reference}s. The operations are rebuilt against the bosk under test by
 * {@link #operationsFor}, which is where each value's type is recovered from its path.
 */
public record EncodedOperationScript(String name, List<JsonNode> operations) implements OperationScript {

	@Override
	public List<DriverOperation> operationsFor(Bosk<TestEntity> referenceBosk) throws InvalidTypeException {
		ObjectMapper valueMapper = OperationScriptCodec.valueMapperFor(referenceBosk);
		List<DriverOperation> result = new ArrayList<>(operations.size());
		for (JsonNode node : operations) {
			OperationSpec spec = OperationScriptCodec.decodeSpec(node, referenceBosk, valueMapper);
			result.add(OperationScriptCodec.toDriverOperation(spec, referenceBosk));
		}
		return result;
	}
}
