package works.bosk.boson.types;

import java.util.Map;

public record LowerBoundedWildcardType(DataType lowerBound) implements WildcardType {
	@Override
	public String toString() {
		return "? super " + lowerBound;
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		if (other instanceof LowerBoundedWildcardType(var otherLower)) {
			// Wildcard types are topsy-turvy because they
			// match supertypes, so counterintuitive things can happen.
			//
			// In this case, we have duelling lower-bounded wildcards:
			// When is "? super A" assignable from "? super B"?
			// Answer: when B is assignable from A.
			// (Think "? super String" is assignable from "? super CharSequence".)
			// If we were to call other.isAssignableFrom(lowerBound)
			// in this case, that would check whether
			// "? super B" is assignable from A,
			// ("? super CharSequence" is assignable from String),
			// which would itself check whether A is assignable from B,
			// ("String" is assignable from "CharSequence"),
			// which is backward.
			//
			return otherLower.isAssignableFrom(lowerBound);
		} else {
			// For any other type, the candidate must be a supertype of the lower bound
			return other.isAssignableFrom(lowerBound);
		}
	}

	@Override
	public boolean isBindableFrom(DataType other) {
		if (other instanceof LowerBoundedWildcardType(var otherLower)) {
			return otherLower.isAssignableFrom(lowerBound);
		} else {
			// For any other type, the candidate must be a supertype of the lower bound
			return other.isAssignableFrom(lowerBound);
		}
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return Object.class;
	}

	@Override
	public LowerBoundedWildcardType substitute(Map<String, DataType> actualArguments) {
		return new LowerBoundedWildcardType(lowerBound.substitute(actualArguments));
	}
}
