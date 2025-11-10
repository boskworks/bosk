package works.bosk.boson.types;

import java.util.Map;

/**
 * A {@link KnownType} with at least some type information erased.
 */
public record ErasedType(Class<?> rawClass) implements InstanceType {
	public ErasedType {
		assert !rawClass.isPrimitive();
		assert !rawClass.isArray();
		assert rawClass.getTypeParameters().length > 0;
	}

	@Override
	public String toString() {
		return rawClass.getSimpleName();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		// More lax than most isAssignableFrom implementations.
		// Erased types are used when the user wants to ignore generic type parameters.
		return rawClass.isAssignableFrom(other.leastUpperBoundClass());
	}

	@Override
	public boolean isBindableFrom(DataType other) {
		return other instanceof KnownType k && rawClass.equals(k.rawClass());
	}

	@Override
	public ErasedType substitute(Map<String, DataType> actualArguments) {
		return this;
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		return Map.of();
	}

	@Override
	public boolean hasWildcards() {
		// In effect, all the type parameters of an erased type are wildcards.
		return true;
	}
}
