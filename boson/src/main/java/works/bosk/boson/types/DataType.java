package works.bosk.boson.types;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static works.bosk.boson.types.DataType.BindableOptions.IS_ASSIGNABLE;
import static works.bosk.boson.types.DataType.BindableOptions.IS_BINDABLE;

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
			return new TypeVariable(tv.getName(), Arrays.asList(tv.getBounds()));
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
	 * a variable of type A. This is an extension of {@link Class#isAssignableFrom} to
	 * handle generics. Note, in particular, that it returns {@code false}
	 * for boxing and unboxing conversions, as does {@link Class#isAssignableFrom}.
	 * <p>
	 * Note that this is neither weaker nor stronger than {@link #isBindableFrom}.
	 * Type variables will only accept themselves or other type variables
	 * that are nominally subtypes of them,
	 * but concrete types can be assigned from subtypes.
	 */
	default boolean isAssignableFrom(DataType other) {
		return isBindableFrom(other, IS_ASSIGNABLE, new HashMap<>());
	}

	/**
	 * {@code A.isBindableFrom(B)} if a value of type List<B>
	 * can be passed to a method expecting List<A>.
	 * <p>
	 * Note that this is neither weaker nor stronger than {@link #isAssignableFrom(DataType)}.
	 * Type variables will accept types that conform to their bounds,
	 * but concrete types cannot be assigned from subtypes.
	 */
	default boolean isBindableFrom(DataType other) {
		return isBindableFrom(other, IS_BINDABLE, new HashMap<>());
	}

	/**
	 * @param allowSubtypes if true, allows subtypes of {@code this} to match.
	 *   Says nothing about handling of any type variables encountered:
	 *   those are governed by the language spec.
	 * @param freeVariables if false, unbound type variables match only themselves
	 */
	record BindableOptions(boolean allowSubtypes, boolean freeVariables){
		public static final BindableOptions IS_BINDABLE = new BindableOptions(false, true);
		public static final BindableOptions IS_ASSIGNABLE = new BindableOptions(true, false);

		/**
		 * Often, whether subtypes are allowed varies during the matching process.
		 * This lets us assert a new value for that flag while keeping the other the same.
		 */
		public BindableOptions withAllowSubtypes(boolean v) {
			return new BindableOptions(v, this.freeVariables);
		}

		public boolean matches(Class<?> target, Class<?> candidate) {
			return allowSubtypes() ? target.isAssignableFrom(candidate) : target.equals(candidate);
		}
	}

	boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar);

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
