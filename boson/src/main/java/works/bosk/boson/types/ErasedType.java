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
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		if (options.allowSubtypes()) {
			return switch (other) {
				case NullType _ -> true;
				case KnownType kt ->
					rawClass.isAssignableFrom(kt.rawClass());
				case TypeVariable tv -> tv.bounds().stream().allMatch(bound ->
					this.isBindableFrom(DataType.of(bound), options, bindingsSoFar));
				case WildcardType w ->
					this.isBindableFrom(w.capture(), options, bindingsSoFar);
				case CapturedType ct ->
					this.isBindableFrom(ct.upperBound(), options, bindingsSoFar)
						&& ct.lowerBound().isBindableFrom(this, options, bindingsSoFar);
				case UnknownArrayType u ->
					rawClass().isAssignableFrom(u.elementType().leastUpperBoundClass().arrayType());
			};
		} else {
			return (other instanceof KnownType kt) && rawClass.equals(kt.rawClass());
		}
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
