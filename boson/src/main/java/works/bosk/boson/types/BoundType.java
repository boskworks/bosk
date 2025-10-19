package works.bosk.boson.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;

/**
 * An {@link InstanceType} accompanied by generic type information.
 * <p>
 * The parameters could be {@link UnknownType}s, so this doesn't
 * necessarily mean it's a fully known type.
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

	public boolean isAssignableFrom(DataType candidateType) {
		if (!(candidateType instanceof KnownType candidate)) {
			// Known types can't be assignable from unknown ones
			return false;
		}
		if (!rawClass().isAssignableFrom(candidate.rawClass())) {
			return false;
		}

		return switch (candidate) {
			case ArrayType _ -> Object.class.equals(rawClass());
			case PrimitiveType _ -> false; // No instance type matches any primitive
			case works.bosk.boson.types.BoundType bt -> isAssignableFrom(bt);
			case ErasedType _ -> true; // Seems aggressive, but people use erased types when they don't want to think about generics
		};

	}

	private boolean isAssignableFrom(works.bosk.boson.types.BoundType candidate) {
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
		List<DataType> resolvedArguments = new ArrayList<>();
		typeArguments().forEach(arg -> {
			switch (arg) {
				case UpperBoundedWildcardType(var upperBound)
					when (upperBound instanceof TypeVariable(String name)) -> {
					resolvedArguments.add(new UpperBoundedWildcardType(
						typeVariableBindings.getOrDefault(name, upperBound)
					));
				}
				case LowerBoundedWildcardType(var lowerBound)
					when (lowerBound instanceof TypeVariable(String name)) -> {
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
			if (!isAssignableTypeArgument(patternArg, candidateParameter)) {
				return false;
			}
		}

		return true;
	}

	private boolean isAssignableTypeArgument(DataType patternArg, DataType candidateType) {
		if (patternArg.equals(candidateType)) {
			// Trivially assignable
			return true;
		}

		if (!(candidateType instanceof KnownType candidate)) {
			// If the candidate isn't a known type, we're bailing out
			return false;
		}

		return switch (patternArg) {
			case UnboundedWildcardType _, TypeVariable _ -> true;
			case UpperBoundedWildcardType(var upperBound) -> upperBound.isAssignableFrom(candidate);
			case LowerBoundedWildcardType(var lowerBound) -> candidate.isAssignableFrom(lowerBound);
			default -> patternArg.equals(candidate); // Generics are neither covariant nor contravariant
		};

	}

	@Override
	public String toString() {
		if (this.bindings().isEmpty()) {
			return this.rawClass().getSimpleName();
		} else {
			return this.rawClass().getSimpleName() + "<"
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
}
