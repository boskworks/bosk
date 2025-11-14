package works.bosk.boson.types;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Note that we don't turn the bounds into {@link DataType}s eagerly,
 * because the bounds on type variables can be self-referential,
 * and this would lead to infinite recursion.
 * Wildcards don't have this problem, so their bounds are eagerly converted.
 * <p>
 * When processing the bounds, you can always convert them to {@link DataType}s
 * as needed, but be careful to avoid infinite recursion.
 */
public record TypeVariable(String name, List<Type> bounds) implements UnknownType {

	public TypeVariable {
		assert !bounds.isEmpty():
			"TypeVariable must have at least one bound (Object if unbounded)";
	}

	public TypeVariable(String name, Type... bounds) {
		this(name, (bounds.length == 0)? NO_BOUNDS : List.of(bounds));
	}

	public static TypeVariable unbounded(String name) {
		return new TypeVariable(name, NO_BOUNDS);
	}

	@Override
	public String toString() {
		if (NO_BOUNDS.equals(bounds)) {
			return name;
		} else {
			StringBuilder sb = new StringBuilder();
			sb.append(name);
			sb.append(" extends ");
			for (int i = 0; i < bounds.size(); i++) {
				if (i > 0) {
					sb.append(" & ");
				}
				sb.append(bounds.get(i).getTypeName());
			}
			return sb.toString();
		}
	}

	@Override
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		var existing = bindingsSoFar.get(name);
		if (existing == null) {
			// Try to establish a new binding
			if (options.freeVariables()) {
				for (var bound : bounds) {
					var substituted = DataType.of(bound).substitute(bindingsSoFar);
					// When considering bounds, subtypes are allowed in any context
					if (!substituted.isBindableFrom(other, options.withAllowSubtypes(true), bindingsSoFar)) {
						return false;
					}
				}
				bindingsSoFar.put(name, other);
				return true;
			} else {
				// The variable is not free to bind; it must match itself
				bindingsSoFar.put(name, this);
				return this.equals(other);
			}
		} else {
			return existing.isBindableFrom(other, options, bindingsSoFar);
		}
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return switch (bounds.size()) {
			case 0 -> { throw new AssertionError("TypeVariable must have at least one bound"); }
			case 1 -> DataType.of(bounds.getFirst()).leastUpperBoundClass();
			default -> Object.class; // TODO: Do better. Check the JLS to see what we're supposed to do here.
		};
	}

	@Override
	public DataType substitute(Map<String, DataType> actualArguments) {
		DataType candidate = actualArguments.get(name);
		if (candidate != null) {
			return candidate;
		} else {
			return this;
		}
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		var map = new LinkedHashMap<String, DataType>();

		// This is where the rubber meets the road:
		// we're binding this variable to `other`.
		map.put(name, other);

		if (other instanceof BoundType otherBoundType) {
			// We may be able to infer more bindings from how this variable's bounds
			// match up with other.
			bounds.forEach(bound -> {
				if (DataType.of(bound) instanceof BoundType bt) {
					var bindings = bt.bindings();
					for (int i = 0; i < bindings.size(); i++) {
						if (bindings.get(i) instanceof TypeVariable tv) {
							map.put(tv.name, otherBoundType.parameterType(bt.rawClass(), i));
						}
					}
				}
			});
		}

		return Map.copyOf(map);
	}

	@Override
	public boolean hasWildcards() {
		// The type bounds can contain wildcards, but the important thing is that
		// if this type variable were substituted with a wildcard-free type, the
		// result would be wildcard-free regardless of the bounds, so we return false here.
		return false;
	}

	public static final List<Type> NO_BOUNDS = List.of(Object.class);

}
