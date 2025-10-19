package works.bosk.boson.types;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
			return new TypeVariable(tv.getName());
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

	// TODO: I think we also want a kind of unification-match, where subtyping is not considered outside of type bounds
	boolean isAssignableFrom(DataType other);

	default boolean isAssignableFrom(Type type) {
		return isAssignableFrom(DataType.of(type));
	}

	default boolean isAssignableFrom(TypeReference<?> ref) {
		return isAssignableFrom(DataType.of(ref));
	}

	DataType substitute(Map<String, DataType> actualArguments);
}
