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
		return other instanceof KnownType k && rawClass.isAssignableFrom(k.rawClass());
	}

	@Override
	public ErasedType substitute(Map<String, DataType> actualArguments) {
		return this;
	}
}
