package works.bosk.boson.types;

import java.util.Map;

/**
 * Represents a class or interface type.
 */
sealed public interface InstanceType extends KnownType permits BoundType, ErasedType {

	/**
	 * @return the type of the generic parameter of {@code targetClass} used by this type.
	 * For example, if this type inherits (directly or indirectly) {@code List<String>),
	 * then calling {@code parameterType(List.class, 0)} will return {@link DataType#STRING}.
	 */
	default DataType parameterType(Class<?> targetClass, int parameterIndex) {
		assert targetClass.isAssignableFrom(this.rawClass());
		if (targetClass.equals(this.rawClass())) {
			return switch (this) {
				case BoundType g -> g.bindings().get(parameterIndex);
				case ErasedType _ -> new UnboundedWildcardType();
			};
		} else {
			// First, find some immediate supertype that is a subtype of targetClass.
			works.bosk.boson.types.InstanceType immediateSuperType = null;
			if (targetClass.isInterface()) {
				for (var i : this.rawClass().getGenericInterfaces()) {
					immediateSuperType = (works.bosk.boson.types.InstanceType) DataType.of(i);
					if (targetClass.isAssignableFrom(immediateSuperType.rawClass())) {
						break;
					} else {
						immediateSuperType = null;
					}
				}
			}
			if (immediateSuperType == null) {
				// If it's not an interface, then it must be from our superclass
				immediateSuperType = (works.bosk.boson.types.InstanceType) DataType.of(rawClass().getGenericSuperclass());
			}

			// For an example, suppose...
			// class S extends T<String> where class T<V> extends List<V>
			// ...and we're calling S.parameterType(List.class, 0).
			// The right answer is String.

			// First, let's recurse into the superclass...
			var candidate = immediateSuperType.parameterType(targetClass, parameterIndex);

			if (candidate instanceof TypeVariable tv) {
				// Now the candidate type would be V, in which case
				// we want to map that to String.
				var typeParameters = rawClass().getTypeParameters();
				for (int i = 0; i < typeParameters.length; i++) {
					if (typeParameters[i].getName().equals(tv.name())) {
						return parameterType(rawClass(), i);
					}
				}
				// TODO: There's something fishy at this point.
				// In the motivating example above, we do want to return String;
				// but suppose S<X> extends T<X>, and we're calling this for S<V>.
				// Then we want to return V. The confusing part is, there are
				// two distinct variables here called V, and all this loop does
				// is scan by name, so that's insufficient.
				// We need a couple of testcases for this, and we might even
				// need to distinguish type variables by their getGenericDeclaration(),
				// which is currently absent from our TypeVariable,
				// so maybe we need to a helper version of ParameterType
				// that works on java.lang.reflect stuff and then converts to a DataType at the end.
			}
			return candidate;
		}
	}

	@Override
	InstanceType substitute(Map<String, DataType> actualArguments);
}
