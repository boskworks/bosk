package works.bosk.boson.mapping.spec;

import java.util.Map;
import works.bosk.boson.types.DataType;

/**
 * Represents a JSON <em>value</em> that may be <em>null</em>.
 * (Other nodes are not required to handle null values.)
 */
public record MaybeNullSpec(JsonValueSpec child) implements JsonValueSpec {
	public MaybeNullSpec {
		assert !child.dataType().leastUpperBoundClass().isPrimitive();
	}

	@Override
	public DataType dataType() {
		return this.child().dataType();
	}

	@Override
	public String briefIdentifier() {
		return "NullOr_" + child().briefIdentifier();
	}

	@Override
	public MaybeNullSpec specialize(Map<String, DataType> actualArguments) {
		return new MaybeNullSpec(child.specialize(actualArguments));
	}

	@Override
	public String toString() {
		return child + "?";
	}
}
