package works.bosk.boson.types;

import java.util.Map;

/**
 * A type defined in JLS 4.1 as having no values (other than null).
 * The null type is assignable to anything.
 * It is an {@link UnknownType} because there is no valid value to
 * return from {@link KnownType#rawClass()}. ({@link Void} is not quite the same.)
 */
record NullType() implements UnknownType {

	@Override
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		return this.equals(other);
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return Object.class;
	}

	@Override
	public DataType substitute(Map<String, DataType> actualArguments) {
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
		return "Null";
	}
}
