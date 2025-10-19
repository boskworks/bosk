package works.bosk.boson.types;

import java.util.Map;

public record UpperBoundedWildcardType(DataType upperBound) implements WildcardType {
	@Override
	public String toString() {
		return "? extends " + upperBound;
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return other instanceof KnownType && upperBound.isAssignableFrom(other);
	}

	@Override
	public UpperBoundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return new UpperBoundedWildcardType(upperBound.substitute(actualArguments));
	}
}
