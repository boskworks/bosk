package works.bosk.boson.types;

import java.lang.reflect.Type;

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
}
