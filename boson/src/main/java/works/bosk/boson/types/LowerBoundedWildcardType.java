package works.bosk.boson.types;

import java.util.Map;

public record LowerBoundedWildcardType(DataType lowerBound) implements WildcardType {
	@Override
	public String toString() {
		return "? super " + lowerBound;
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return Object.class;
	}

	@Override
	public LowerBoundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return new LowerBoundedWildcardType(lowerBound.substitute(actualArguments));
	}

	@Override
	public CapturedType capture() {
		return new CapturedType(lowerBound, OBJECT);
	}
}
