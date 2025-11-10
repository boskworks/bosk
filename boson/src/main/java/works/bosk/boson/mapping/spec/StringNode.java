package works.bosk.boson.mapping.spec;

import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

/**
 * Represents a JSON string as a {@link String}.
 */
public record StringNode() implements ScalarSpec {
	@Override
	public String toString() {
		return "StringNode";
	}

	@Override
	public KnownType dataType() {
		return DataType.STRING;
	}

	@Override
	public String briefIdentifier() {
		return "String";
	}
}
