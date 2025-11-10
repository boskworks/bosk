package works.bosk.boson.types;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public sealed interface DataType permits KnownType, UnknownType {
	KnownType VOID = new PrimitiveType(void.class);
	PrimitiveType BOOLEAN = new PrimitiveType(boolean.class);
	PrimitiveType BYTE = new PrimitiveType(byte.class);
	PrimitiveType SHORT = new PrimitiveType(short.class);
	PrimitiveType INT = new PrimitiveType(int.class);
	PrimitiveType LONG = new PrimitiveType(long.class);
	PrimitiveType FLOAT = new PrimitiveType(float.class);
	PrimitiveType DOUBLE = new PrimitiveType(double.class);
	PrimitiveType CHAR = new PrimitiveType(char.class);
	BoundType STRING = (BoundType) DataType.of(String.class);
	BoundType OBJECT = (BoundType) DataType.of(Object.class);

	static KnownType known(Type type) {
		if (of(type) instanceof KnownType kt) {
			return kt;
		} else {
			throw new IllegalArgumentException("Type is not a KnownType: " + type);
		}
	}

	static KnownType known(TypeReference<?> ref) {
		return (KnownType) of(ref);
	}

	static DataType of(Type type) {
		if (type instanceof Class<?> clazz) {
			if (clazz.isArray()) {
				var ct = of(clazz.getComponentType());
				if (ct instanceof KnownType kt) {
					return new ArrayType(kt);
				} else {
					return new UnknownArrayType((UnknownType) ct);
				}
			} else if (clazz.isPrimitive()) {
				return new PrimitiveType(clazz);
			} else if (clazz.getTypeParameters().length == 0) {
				return new BoundType(clazz, List.of());
			} else {
				return new ErasedType(clazz);
			}
		} else if (type instanceof ParameterizedType pt) {
			return new BoundType(
				(Class<?>) pt.getRawType(),
				Stream.of(pt.getActualTypeArguments()).map(DataType::of).toList());
		} else if (type instanceof java.lang.reflect.TypeVariable<?> tv) {
			Type[] bounds = tv.getBounds();
			if (bounds.length == 1 && bounds[0].equals(Object.class)) {
				// Prefer to represent as unbounded
				return new TypeVariable(tv.getName(), List.of());
			} else {
				return new TypeVariable(tv.getName(), Arrays.asList(bounds));
			}
		} else if (type instanceof java.lang.reflect.WildcardType w) {
			return ofWildcard(w);
		} else if (type instanceof GenericArrayType t) {
			var elementType = DataType.of(t.getGenericComponentType());
			return switch (elementType) {
				case KnownType e -> new ArrayType(e);
				case UnknownType e -> new UnknownArrayType(e);
			};
		}
		throw new IllegalArgumentException("Unsupported type: " + type);
	}

	static DataType ofWildcard(java.lang.reflect.WildcardType wildcardType) {
		assert wildcardType.getLowerBounds().length <= 1 && wildcardType.getUpperBounds().length <= 1;
		if (wildcardType.getLowerBounds().length == 1) {
			assert wildcardType.getUpperBounds()[0].equals(Object.class);
			return new LowerBoundedWildcardType(DataType.of(wildcardType.getLowerBounds()[0]));
		} else if (wildcardType.getUpperBounds()[0].equals(Object.class)) {
			return new UnboundedWildcardType();
		} else {
			return new UpperBoundedWildcardType(DataType.of(wildcardType.getUpperBounds()[0]));
		}
	}

	static DataType of(TypeReference<?> ref) {
		return of(ref.reflectionType());
	}

	/**
	 * {@code A.isAssignableFrom(B)} if a value of type B can be assigned to
	 * a variable of type A.
	 * <p>
	 * Note that this is neither weaker nor stronger than {@link #isBindableFrom(DataType)}.
	 * Type variables will only accept themselves or other type variables
	 * that are nominally subtypes of them,
	 * but concrete types can be assigned from subtypes.
	 */
	boolean isAssignableFrom(DataType other);

	/**
	 * {@code A.isBindableFrom(B)} if a value of type List<B>
	 * can be passed to a method expecting List<A>.
	 * <p>
	 * Note that this is neither weaker nor stronger than {@link #isAssignableFrom(DataType)}.
	 * Type variables will accept types that conform to their bounds,
	 * but concrete types cannot be assigned from subtypes.
	 */
	boolean isBindableFrom(DataType other);

	default boolean isAssignableFrom(Type type) {
		return isAssignableFrom(DataType.of(type));
	}

	default boolean isAssignableFrom(TypeReference<?> ref) {
		return isAssignableFrom(DataType.of(ref));
	}

	/**
	 * @return The most specific common supertype of all possible types
	 * represented by this DataType.
	 */
	Class<?> leastUpperBoundClass();

	DataType substitute(Map<String, DataType> actualArguments);

	Map<String, DataType> bindingsFor(DataType other);

	/**
	 * @return true if this type contains any {@link WildcardType} or {@link ErasedType}.
	 */
	boolean hasWildcards();
}
