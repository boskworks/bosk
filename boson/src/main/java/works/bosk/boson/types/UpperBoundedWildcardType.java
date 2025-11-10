package works.bosk.boson.types;

import java.util.Map;

public record UpperBoundedWildcardType(DataType upperBound) implements WildcardType {
	@Override
	public Class<?> leastUpperBoundClass() {
		return upperBound.leastUpperBoundClass();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return upperBound.isAssignableFrom(other);
	}

	@Override
	public boolean isBindableFrom(DataType other) {
		return upperBound.isAssignableFrom(other);
	}

	@Override
	public UpperBoundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return new UpperBoundedWildcardType(upperBound.substitute(actualArguments));
	}

	@Override
	public String toString() {
		return "? extends " + upperBound;
	}
}
