package works.bosk.boson.types;

import java.util.Map;

public record PrimitiveType(Class<?> rawClass) implements KnownType {
	public PrimitiveType {
		assert rawClass.isPrimitive();
	}

	/**
	 * This is a bit artificial, since primitive types can't be type arguments.
	 * We do allow them in type arguments, though, so we treat them as we would
	 * a reference type with no parameters.
	 */
	@Override
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		if (other instanceof PrimitiveType(var otherRawClass)) {
			return options.allowSubtypes() // This ternary is pedantic because primitives have no subtypes
				? rawClass.isAssignableFrom(otherRawClass)
				: rawClass.equals(otherRawClass);
		} else {
			return false;
		}
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
