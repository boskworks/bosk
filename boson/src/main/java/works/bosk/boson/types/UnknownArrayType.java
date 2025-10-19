package works.bosk.boson.types;

import java.util.Map;

public record UnknownArrayType(UnknownType elementType) implements UnknownType {
	@Override
	public String toString() {
		return elementType + "[]";
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return switch (other) {
			case ArrayType(var otherElementType) -> elementType.isAssignableFrom(otherElementType);
			default -> false;
		};
	}

	@Override
	public DataType substitute(Map<String, DataType> actualArguments) {
		var newElementType = elementType.substitute(actualArguments);
		return switch (newElementType) {
			case KnownType k -> new ArrayType(k);
			case UnknownType u -> new UnknownArrayType(u);
		};
	}
}
