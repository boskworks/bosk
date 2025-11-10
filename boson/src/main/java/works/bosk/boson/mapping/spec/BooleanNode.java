package works.bosk.boson.mapping.spec;

import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

/**
 * Represents a JSON {@code true} or {@code false} value as a {@code boolean}.
 */
public record BooleanNode() implements ScalarSpec {
	@Override
	public String toString() {
		return "boolean";
	}

	@Override
	public KnownType dataType() {
		return DataType.BOOLEAN;
	}

	@Override
	public String briefIdentifier() {
		return "Boolean";
	}
}
