package works.bosk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Delegate;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.Types;

import static java.lang.String.format;
import static java.lang.reflect.Modifier.STATIC;

/**
 * Collection of utilities for implementing {@link Reference}s.
 *
 * @author pdoyle
 *
 */
public final class ReferenceUtils {

	@SuppressWarnings("unused")
	private interface CovariantOverrides<T> {
		Reference<T> boundBy(BindingEnvironment bindings);
		Reference<T> boundBy(Path definitePath);
		Reference<T> boundTo(Identifier... ids);
	}

	@RequiredArgsConstructor
	@Value // Unusual equals and hashcode doesn't suit being a record
	static class CatalogRef<E extends Entity> implements CatalogReference<E> {
		@Delegate(excludes = CovariantOverrides.class)
		Reference<Catalog<E>> ref;
		Class<E> entryClass;

		@Override
		public CatalogReference<E> boundBy(BindingEnvironment bindings) {
			return new CatalogRef<>(ref.boundBy(bindings), entryClass());
		}

		@Override
		public Reference<E> then(Identifier id) {
			try {
				return ref.then(entryClass, id.toString());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Entry class must match", e);
			}
		}

		@Override public boolean equals(Object o) {
			if (this == o) {
				return true;
			} else {
				return ref.equals(o);
			}
		}

		@Override public int hashCode() { return ref.hashCode(); }
		@Override public String toString() { return ref.toString(); }
	}

	@Value // Unusual equals and hashcode doesn't suit being a record
	static class ListingRef<E extends Entity> implements ListingReference<E> {
		@Delegate(excludes = {CovariantOverrides.class})
		Reference<Listing<E>> ref;

		@Override
		public ListingReference<E> boundBy(BindingEnvironment bindings) {
			return new ListingRef<>(ref.boundBy(bindings));
		}

		@Override
		public Reference<ListingEntry> then(Identifier id) {
			try {
				return ref.then(ListingEntry.class, id.toString());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Entry class must match", e);
			}
		}

		@Override public boolean equals(Object o) {
			if (this == o) {
				return true;
			} else {
				return ref.equals(o);
			}
		}

		@Override public int hashCode() { return ref.hashCode(); }
		@Override public String toString() { return ref.toString(); }
	}

	@RequiredArgsConstructor
	static final class SideTableRef<K extends Entity,V> implements SideTableReference<K,V> {
		@Delegate(excludes = {CovariantOverrides.class})
		private final Reference<SideTable<K,V>> ref;
		private final Class<K> keyClass;
		private final Class<V> valueClass;

		public Class<K> keyClass() {
			return keyClass;
		}

		public Class<V> valueClass() {
			return valueClass;
		}

		@Override
		public Reference<V> then(Identifier id) {
			try {
				return ref.then(valueClass, id.toString());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Value class must match", e);
			}
		}

		@Override public Reference<V> then(K key) { return this.then(key.id()); }

		@Override
		public SideTableReference<K, V> boundBy(BindingEnvironment bindings) {
			return new SideTableRef<>(ref.boundBy(bindings), keyClass(), valueClass());
		}

		@Override public boolean equals(Object o) {
			if (this == o) {
				return true;
			} else {
				return ref.equals(o);
			}
		}

		@Override public int hashCode() { return ref.hashCode(); }
		@Override public String toString() { return ref.toString(); }
	}

	/**
	 * Lookup a type parameter of a given generic class made concrete by a given parameterized subtype.
	 *
	 * <p>
	 * This stuff can get incredibly abstract. Let's consider these specific types as an example:
	 *
	 * <pre>
interface S&lt;A,B> {}
interface I&lt;C,D> implements S&lt;A,B> {}
class C&lt;T> implements I&lt;T,Integer> {}
...
C&lt;String> someField;
	 * </pre>
	 *
	 * Note that the type <code>C&lt;String></code> implements (indirectly)
	 * <code>S&lt;String,Integer></code>. That means in the context of <code>C&lt;String></code>,
	 * type parameter 0 of <code>S</code> would be <code>String</code>.
	 *
	 * <p>
	 * Hence, if you use reflection to get a {@link Field} <code>f</code> for
	 * <code>someField</code>, then calling
	 * <code>parameterType(f.getGenericType(), S.class, 0)</code> would return
	 * <code>String.class</code>.
	 *
	 * @param parameterizedType The {@link Type} providing the context for the parameter lookup
	 * @param genericClass The generic class whose parameter you want
	 * @param index The position of the desired parameter within the parameter list of <code>genericClass</code>
	 * @return the {@link Type} of the desired parameter
	 */
	public static Type parameterType(Type parameterizedType, Class<?> genericClass, int index) {
		Class<?> actualClass = rawClass(parameterizedType);
		assert genericClass.isAssignableFrom(actualClass): genericClass.getSimpleName() + " must be assignable from " + parameterizedType;
		if (actualClass == genericClass) {
			return parameterType(parameterizedType, index);
		} else try {
			// We're dealing with inheritance. Find which of our
			// superclass/superinterfaces to pursue.
			//
			// Repeated inheritance of the same interface is not a problem
			// because Java's generics require that multiply-implemented
			// interfaces must have consistent types, so any occurrence of the
			// interface will serve.
			//
			Type supertype = lowestCompatibleSupertype(genericClass, actualClass);

			// Recurse with supertype
			Type returned = parameterType(supertype, genericClass, index);

			return resolveTypeVariables(returned, parameterizedType);
		} catch (AssertionError e) {
			// Help diagnose assertion errors from recursive calls
			throw new AssertionError(format("parameterType(%s, %s, %s): %s", parameterizedType, genericClass, index, e.getMessage()), e);
		}
	}

	private static Type lowestCompatibleSupertype(Class<?> requiredSupertype, Class<?> givenClass) {
		Type supertype = givenClass.getGenericSuperclass();
		if (supertype == null || !requiredSupertype.isAssignableFrom(rawClass(supertype))) {
			// Must come from interface inheritance
			supertype = null; // Help catch errors
			for (Type candidate: givenClass.getGenericInterfaces()) {
				if (requiredSupertype.isAssignableFrom(rawClass(candidate))) {
					supertype = candidate;
					break;
				}
			}
			assert supertype != null: "If requiredSupertype isAssignableFrom givenClass, and they're not equal, then it must be assignable from something givenClass inherits";
		}
		return supertype;
	}

	/**
	 * @param typeWithVariables a {@link Type} that may or may not contain references to {@link TypeVariable}s.
	 * @param environmentType the type that defines what those variables mean
	 * @return a new type whose variables are all bound by the definitions in <code>environmentType</code>
	 */
	private static Type resolveTypeVariables(Type typeWithVariables, Type environmentType) {
		if (typeWithVariables instanceof TypeVariable) {
			// The recursive call has typeWithVariables us one of the type variables
			// from our own generic class.  For example, if environmentType
			// were C<String> and C was declared as C<T> extends S<U>, then
			// `typeWithVariables` is T, and it's our job here to resolve it back to String.
			Class<?> parameterizedClass = rawClass(environmentType);
			TypeVariable<?>[] typeVariables = parameterizedClass.getTypeParameters();
			for (int i = 0; i < typeVariables.length; i++) {
				if (typeWithVariables.equals(typeVariables[i])) {
					return parameterType(environmentType, i);
				}
			}
			throw new AssertionError("Expected type variable match for " + typeWithVariables + " in " + parameterizedClass.getSimpleName() + " type parameters: " + Arrays.toString(parameterizedClass.getTypeParameters()));
		} else if (typeWithVariables instanceof ParameterizedType pt) {
			Type[] resolvedParameters = Stream.of(pt.getActualTypeArguments())
				.map(t -> resolveTypeVariables(t, environmentType))
				.toArray(Type[]::new);
			return Types.parameterizedType(rawClass(pt), resolvedParameters);
		} else {
			return typeWithVariables;
		}
	}

	static Type parameterType(Type parameterizedType, int index) {
		return ((ParameterizedType)parameterizedType).getActualTypeArguments()[index];
	}

	public static Class<?> rawClass(Type sourceType) {
		if (sourceType instanceof ParameterizedType pt) {
			return (Class<?>)pt.getRawType();
		} else {
			return (Class<?>)sourceType;
		}
	}

	public static Method getterMethod(Class<?> objectClass, String fieldName) throws InvalidTypeException {
		String methodName = fieldName; // fluent
		for (Class<?> c = objectClass; c != Object.class; c = c.getSuperclass()) {
			try {
				Method result = c.getDeclaredMethod(methodName);
				if (result.getParameterCount() != 0) {
					throw new InvalidTypeException("Getter method \"" + methodName + "\" has unexpected arguments: " + Arrays.toString(result.getParameterTypes()));
				} else if ((result.getModifiers() & STATIC) != 0) {
					throw new InvalidTypeException("Getter method \"" + methodName + "\" is static");
				}
				return result;
			} catch (NoSuchMethodException e) {
				// No prob; try the superclass
			}
		}

		// If the program is compiled without parameter info, we'll see the generated name "arg0".
		// In that case, try to give a helpful error message.
		if (methodName.equals("arg0")) {
			throw new InvalidTypeException(objectClass.getSimpleName() + " was compiled without parameter info; see https://github.com/boskworks/bosk#build-settings");
		} else {
			throw new InvalidTypeException("No method \"" + methodName + "()\" in type " + objectClass.getSimpleName());
		}
	}

	public static <T> Constructor<T> theOnlyConstructorFor(Class<T> nodeClass) {
		List<Constructor<?>> constructors = Stream.of(nodeClass.getDeclaredConstructors())
			.filter(ctor -> !ctor.isSynthetic())
			.toList();
		if (constructors.isEmpty()) {
			throw new IllegalArgumentException("No suitable constructor for " + nodeClass.getSimpleName());
		} else if (constructors.size() >= 2) {
			throw new IllegalArgumentException("Ambiguous constructor list for " + nodeClass.getSimpleName() + ": " + constructors);
		}
		@SuppressWarnings("unchecked")
		Constructor<T> theConstructor = (Constructor<T>) constructors.getFirst();
		return theConstructor;
	}

	/**
	 * @see Class#getRecordComponents()
	 */
	public static <T> Constructor<T> getCanonicalConstructor(Class<T> cls) {
		assert Record.class.isAssignableFrom(cls): cls.getSimpleName() + " must be a record";
		Class<?>[] paramTypes =
			Arrays.stream(cls.getRecordComponents())
				.map(RecordComponent::getType)
				.toArray(Class<?>[]::new);
		try {
			return cls.getDeclaredConstructor(paramTypes);
		} catch (NoSuchMethodException e) {
			throw new AssertionError("Record class must have a canonical constructor; is " + cls.getSimpleName() + " a record class?", e);
		}
	}

	public static Map<String, Method> gettersForConstructorParameters(Class<?> nodeClass) throws InvalidTypeException {
		Iterable<String> names = Stream
			.of(getCanonicalConstructor(nodeClass).getParameters())
			.map(Parameter::getName)
			::iterator;
		Map<String, Method> result = new LinkedHashMap<>();
		for (String name: names) {
			result.put(name, getterMethod(nodeClass, name));
		}
		return result;
	}

}
