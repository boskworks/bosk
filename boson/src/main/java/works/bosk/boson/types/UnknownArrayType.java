package works.bosk.boson.types;

import java.util.Map;

import static works.bosk.boson.types.ArrayType.arrayIsBindableFrom;

public record UnknownArrayType(UnknownType elementType) implements UnknownType {
	@Override
	public String toString() {
		return "«" + elementType + "»[]";
	}

	@Override
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		return arrayIsBindableFrom(this, elementType, other, options, bindingsSoFar);
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
