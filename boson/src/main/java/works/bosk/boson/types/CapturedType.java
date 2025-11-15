package works.bosk.boson.types;

import java.util.Map;

/**
 * The result of a Capture Conversion, as specified in JLS §5.1.10.
 * @param lowerBound
 * @param upperBound
 */
public record CapturedType(
	DataType lowerBound,
	DataType upperBound
) implements UnknownType {
	@Override
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		// For bounds checking, we always allow subtypes
		BindableOptions boundsOptions = options.withAllowSubtypes(true);
		return
			upperBound().isBindableFrom(other, boundsOptions, bindingsSoFar)
			&& (options.allowSubtypes() // Lower bound is irrelevant if subtypes are allowed
				|| other.isBindableFrom(lowerBound(), boundsOptions, bindingsSoFar));
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return upperBound().leastUpperBoundClass();
	}

	@Override
	public DataType substitute(Map<String, DataType> actualArguments) {
		return new CapturedType(lowerBound.substitute(actualArguments), upperBound.substitute(actualArguments));
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		return Map.of();
	}

	@Override
	public boolean hasWildcards() {
		return true;
	}
}
