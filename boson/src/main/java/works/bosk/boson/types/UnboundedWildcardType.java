package works.bosk.boson.types;

import java.util.Map;

public record UnboundedWildcardType() implements WildcardType {
	@Override
	public String toString() {
		return "?";
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return Object.class;
	}

	@Override
	public UnboundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return this;
	}

	@Override
	public CapturedType capture() {
		return new CapturedType(new NullType(), OBJECT);
	}
}
