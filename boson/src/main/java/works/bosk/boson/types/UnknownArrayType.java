package works.bosk.boson.types;

import java.util.Map;

public record UnknownArrayType(UnknownType elementType) implements UnknownType {
	@Override
	public String toString() {
		return "«" + elementType + "»[]";
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return switch (other) {
			case ArrayType(var otherElementType) -> elementType.isAssignableFrom(otherElementType);
			default -> false;
		};
	}

	@Override
	public boolean isBindableFrom(DataType other) {
		// TODO: Double-check the semantics here and in ArrayType
		return switch (other) {
			case ArrayType(var otherElementType) -> elementType.isBindableFrom(otherElementType);
			default -> false;
		};
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return Object.class;
	}

	@Override
	public DataType substitute(Map<String, DataType> actualArguments) {
		var newElementType = elementType.substitute(actualArguments);
		return switch (newElementType) {
			case KnownType k -> new ArrayType(k);
			case UnknownType u -> new UnknownArrayType(u);
		};
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		assert this.isAssignableFrom(other);
		return switch (other) {
			case ArrayType(var e) -> elementType.bindingsFor(e);
			case UnknownArrayType(var e) -> elementType.bindingsFor(e);
			default -> throw new IllegalArgumentException("wat");
		};
	}

	@Override
	public boolean hasWildcards() {
		return elementType().hasWildcards();
	}
}
