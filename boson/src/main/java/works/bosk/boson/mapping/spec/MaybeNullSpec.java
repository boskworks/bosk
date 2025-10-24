package works.bosk.boson.mapping.spec;

import works.bosk.boson.types.KnownType;

/**
 * Represents a JSON <em>value</em> that may be <em>null</em>.
 * (Other nodes are not required to handle null values.)
 */
public record MaybeNullSpec(JsonValueSpec child) implements JsonValueSpec {
	public MaybeNullSpec {
		assert !child.dataType().rawClass().isPrimitive();
	}

	@Override
	public String toString() {
		return child + "?";
	}

	public KnownType dataType() {
		return this.child().dataType();
	}

	@Override
	public String briefIdentifier() {
		return "NullOr_" + child().briefIdentifier();
	}
}
