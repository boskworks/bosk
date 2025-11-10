package works.bosk.boson.types;

import java.util.Map;

public record PrimitiveType(Class<?> rawClass) implements KnownType {
	public PrimitiveType {
		assert rawClass.isPrimitive();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return rawClass.isAssignableFrom(other.leastUpperBoundClass());
	}

	/**
	 * This is a bit artificial, since primitive types can't be type arguments.
	 * We do allow them in type arguments, though, so we treat them as we would
	 * a reference type with no parameters.
	 */
	@Override
	public boolean isBindableFrom(DataType other) {
		return other instanceof PrimitiveType(var otherRawClass)
			&& rawClass.equals(otherRawClass);
	}

	@Override
	public PrimitiveType substitute(Map<String, DataType> actualArguments) {
		return this;
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		return Map.of();
	}

	@Override
	public boolean hasWildcards() {
		return false;
	}

	@Override
	public String toString() {
		return rawClass.getSimpleName();
	}
}
