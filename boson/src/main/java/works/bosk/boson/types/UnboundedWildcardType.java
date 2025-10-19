package works.bosk.boson.types;

import java.util.Map;

public record UnboundedWildcardType() implements WildcardType {
	@Override
	public String toString() {
		return "?";
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return other instanceof KnownType;
	}

	@Override
	public UnboundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return this;
	}
}
