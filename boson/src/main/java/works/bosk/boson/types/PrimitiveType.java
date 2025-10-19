package works.bosk.boson.types;

import java.util.Map;

public record PrimitiveType(Class<?> rawClass) implements KnownType {
	public PrimitiveType {
		assert rawClass.isPrimitive();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return other instanceof works.bosk.boson.types.PrimitiveType(var otherRawClass)
			&& rawClass.isAssignableFrom(otherRawClass);
	}

	@Override
	public PrimitiveType substitute(Map<String, DataType> actualArguments) {
		return this;
	}

	@Override
	public String toString() {
		return rawClass.getSimpleName();
	}
}
