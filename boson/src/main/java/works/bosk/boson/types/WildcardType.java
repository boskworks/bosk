package works.bosk.boson.types;

import java.lang.reflect.Type;
import java.util.Map;

sealed public interface WildcardType extends UnknownType permits LowerBoundedWildcardType, UnboundedWildcardType, UpperBoundedWildcardType {
	static UnboundedWildcardType unbounded() {
		return new UnboundedWildcardType();
	}

	static UpperBoundedWildcardType extends_(Type upperBound) {
		return new UpperBoundedWildcardType(DataType.of(upperBound));
	}

	static LowerBoundedWildcardType super_(Type lowerBound) {
		return new LowerBoundedWildcardType(DataType.of(lowerBound));
	}

	CapturedType capture();

	@Override
	default boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		return capture().isBindableFrom(other, options, bindingsSoFar);
	}

	@Override
	default Map<String, DataType> bindingsFor(DataType other) {
		return Map.of();
	}

	@Override
	default boolean hasWildcards() {
		return true;
	}

}
