package works.bosk.libtesting.fuzz;

import tools.jackson.databind.JsonNode;
import works.bosk.testing.drivers.fuzz.OperationKind;

/**
 * The serialized form of a single operation: enough to rebuild it against any bosk with the same
 * state schema.
 *
 * <p>Paths are stored url-encoded, matching the way the state-tree serializers represent
 * references. The value, when present, is stored as a JSON tree produced by the state-tree
 * serializer, so that values follow the same format as the rest of the bosk state.
 *
 * @param kind          the kind of operation
 * @param target        the url-encoded path of the target, or {@code null} for a flush
 * @param precondition  the url-encoded path of the precondition, for conditional operations
 * @param requiredValue the identifier the precondition must have, for conditional operations
 * @param value         the serialized new value, or {@code null} if the operation has none
 */
public record OperationSpec(
	OperationKind kind,
	String target,
	String precondition,
	String requiredValue,
	JsonNode value
) {}
