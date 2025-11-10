package works.bosk.boson.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;

/**
 * An {@link InstanceType} accompanied by generic type information.
 * Not to be confused with a <em>bounded type</em>;
 * this is about <em>bindings</em>, not <em>bounds</em>.
 * <p>
 * For ordinary classes, {@code typeArguments} will be empty,
 * indicating that the class has no type parameters.
 */
public record BoundType(Class<?> rawClass, List<? extends DataType> bindings) implements InstanceType {

	public BoundType(Class<?> rawClass, DataType... bindings) {
		this(rawClass, List.of(bindings));
	}

	DataType typeArgument(int index) {
		return bindings().get(index);
	}

	public Stream<? extends DataType> typeArguments() {
		return this.bindings().stream();
	}

	@Override
	public boolean isAssignableFrom(DataType other) {
		return switch (other) {
			case ArrayType _ -> rawClass().isAssignableFrom(Object[].class);
			case PrimitiveType _ -> false; // No instance type matches any primitive
			case BoundType bt -> isAssignableFrom(bt, BoundType::isAssignableFromTypeParameter);
			case ErasedType(var t)  -> rawClass().isAssignableFrom(t); // Seems aggressive, but people use erased types when they don't want to think about generics
			case TypeVariable(_, var bounds) -> bounds.stream().anyMatch(t -> this.isAssignableFrom(DataType.of(t)));
			case UnknownType _ -> rawClass().isAssignableFrom(other.leastUpperBoundClass());
		};

	}

	/**
	 * @return true if {@code List<patternArg>} is assignable from {@code List<candidate>}.
	 */
	private static boolean isAssignableFromTypeParameter(DataType patternArg, DataType candidate) {
		// isAssignableGenericParameter is too lax because it's meant
		// for invocation site compatibility, so it allows type variables
		// to match things that aren't type variables.

		// isAssignableFrom is too lax because allows types to
		// match their subtypes, but too strict because unknown
		// types hardly match anything.

		return switch (patternArg) {
			case KnownType t -> t.isBindableFrom(candidate);
			case UnknownType t -> t.isAssignableFrom(candidate);
		};

	}

	@Override
	public boolean isBindableFrom(DataType other) {
		return switch (other) {
			case BoundType bt ->
				rawClass().equals(bt.rawClass())
					&& isAssignableFrom(bt, DataType::isBindableFrom);
			case ErasedType(var t)  -> rawClass().equals(t);
			default -> false;
		};
	}

	private boolean isAssignableFrom(InstanceType candidate, BiPredicate<DataType, DataType> typeParameterCheck) {
		if (!rawClass().isAssignableFrom(candidate.rawClass())) {
			return false;
		}

		// Collect all type variable bindings.
		Map<String, DataType> typeVariableBindings = new HashMap<>();
		for (int i = 0; i < bindings().size(); i++) {
			DataType patternArg = typeArgument(i);
			var candidateParameter = candidate.parameterType(rawClass(), i);
			if (patternArg instanceof TypeVariable tv) {
				var existing = typeVariableBindings.put(tv.name(), candidateParameter);
				if (existing != null && !existing.equals(candidateParameter)) {
					// Conflicting bindings for the same type variable
					return false;
				}
			}
		}

		// For each type argument with bounds that are themselves type variables,
		// substitute the values of those bindings.
		// TODO: Double-check the bounds on the type variables?
		List<DataType> resolvedArguments = new ArrayList<>();
		typeArguments().forEach(arg -> {
			switch (arg) {
				case UpperBoundedWildcardType(var upperBound)
					when (upperBound instanceof TypeVariable(String name, _)) -> {
					resolvedArguments.add(new UpperBoundedWildcardType(
						typeVariableBindings.getOrDefault(name, upperBound)
					));
				}
				case LowerBoundedWildcardType(var lowerBound)
					when (lowerBound instanceof TypeVariable(String name, _)) -> {
					resolvedArguments.add(new LowerBoundedWildcardType(
						typeVariableBindings.getOrDefault(name, lowerBound)
					));
				}
				default -> resolvedArguments.add(arg);
			}
		});

		for (int i = 0; i < resolvedArguments.size(); i++) {
			DataType patternArg = resolvedArguments.get(i);
			DataType candidateParameter = candidate.parameterType(this.rawClass(), i);
			if (!typeParameterCheck.test(patternArg, candidateParameter)) {
				return false;
			}
		}

		return true;
	}

	@Override
	public String toString() {
		String simpleName = this.rawClass().getSimpleName();
		if (simpleName.isEmpty()) {
			// Anonymous classes
			simpleName = this.rawClass().getName();
			simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
		}
		if (this.bindings().isEmpty()) {
			return simpleName;
		} else {
			return simpleName + "<"
				+ this.bindings().stream()
				.map(DataType::toString)
				.collect(joining(","))
				+ ">";
		}
	}

	public Map<String, DataType> actualArguments() {
		var typeParameters = rawClass().getTypeParameters();
		assert typeParameters.length == bindings.size();
		Map<String, DataType> map = new HashMap<>();
		for (int i = 0; i < typeParameters.length; i++) {
			map.put(typeParameters[i].getName(), bindings.get(i));
		}
		return unmodifiableMap(map);
	}

	@Override
	public BoundType substitute(Map<String, DataType> actualArguments) {
		return new BoundType(rawClass, bindings.stream()
			.map(ta -> ta.substitute(actualArguments))
			.toList());
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		assert this.isBindableFrom(other):
			this + " must be bindable from " + other + " to get bindings";
		return switch(other) {
			case BoundType(var _, var otherBindings) -> {
				var result = new HashMap<String, DataType>();
				var iter = otherBindings.iterator();
				this.bindings.forEach(b -> result.putAll(b.bindingsFor(iter.next())));
				yield Map.copyOf(result);
			}
			default -> throw new IllegalArgumentException("wat");
		};
	}

	@Override
	public boolean hasWildcards() {
		return bindings.stream().anyMatch(DataType::hasWildcards);
	}
}
