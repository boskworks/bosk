package works.bosk.boson.types;

import java.util.Map;

public record ArrayType(KnownType elementType) implements KnownType {
	@Override
	public Class<?> rawClass() {
		return elementType.rawClass().arrayType();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return other instanceof works.bosk.boson.types.ArrayType(var otherElementType)
			&& elementType.isAssignableFrom(otherElementType);
	}

	@Override
	public ArrayType substitute(Map<String, DataType> actualArguments) {
		return new ArrayType(elementType.substitute(actualArguments));
	}

	@Override
	public String toString() {
		return elementType + "[]";
	}
}
