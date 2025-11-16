package works.bosk.boson.types;

import java.util.Map;

/**
 * A {@link DataType} representing a class or interface type
 * whose {@link #rawClass} is known.
 */
sealed public interface KnownType extends DataType permits ArrayType, InstanceType, PrimitiveType {
	Class<?> rawClass();

	@Override
	default Class<?> leastUpperBoundClass() {
		return rawClass();
	}

	@Override
	KnownType substitute(Map<String, DataType> actualArguments);
}
