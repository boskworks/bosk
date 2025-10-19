package works.bosk.boson.types;

import java.util.Map;

public record LowerBoundedWildcardType(DataType lowerBound) implements WildcardType {
	@Override
	public String toString() {
		return "? super " + lowerBound;
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return other instanceof KnownType && other.isAssignableFrom(lowerBound);
	}

	@Override
	public LowerBoundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return new LowerBoundedWildcardType(lowerBound.substitute(actualArguments));
	}
}
