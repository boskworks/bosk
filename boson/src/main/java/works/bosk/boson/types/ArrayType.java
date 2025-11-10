package works.bosk.boson.types;

import java.util.Map;

public record ArrayType(KnownType elementType) implements KnownType {
	@Override
	public Class<?> rawClass() {
		return elementType.rawClass().arrayType();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return switch (other) {
			case ArrayType(var e) -> elementType.isAssignableFrom(e);
			case UnknownArrayType(var e) -> elementType.isAssignableFrom(e);
			case UpperBoundedWildcardType(var b) -> this.isAssignableFrom(b);
			default -> false;
		};
	}

	@Override
	public boolean isBindableFrom(DataType other) {
		// TODO: Check the semantics here and in UnknownArrayType
		return other instanceof ArrayType(var otherElementType)
			&& elementType.isBindableFrom(otherElementType);
	}

	@Override
	public ArrayType substitute(Map<String, DataType> actualArguments) {
		return new ArrayType(elementType.substitute(actualArguments));
	}

	@Override
	public boolean hasWildcards() {
		return elementType.hasWildcards();
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		assert this.isAssignableFrom(other);
		return switch (other) {
			case ArrayType(var e) -> elementType.bindingsFor(e);
			default -> throw new IllegalArgumentException("wat");
		};
	}

	@Override
	public String toString() {
		return elementType + "[]";
	}
}
