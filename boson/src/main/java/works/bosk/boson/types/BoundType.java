package works.bosk.boson.types;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		return switch (other) {
			case BoundType bt ->
				options.matches(rawClass(), bt.rawClass())
					&& argsAreBindable(bt, options, bindingsSoFar);
			case ErasedType(var t)  -> options.matches(rawClass(), t); // Erased types are pretty permissive
			case TypeVariable(var name, var bounds) -> {
				var existing = bindingsSoFar.get(name);
				if (existing == null) {
					bindingsSoFar.put(name, this);
					yield options.allowSubtypes() // If subtypes aren't allowed, type variables can't match bound types
						&& bounds.stream().anyMatch(bound ->
						this.isBindableFrom(DataType.of(bound), options, bindingsSoFar));
				} else {
					yield this.isBindableFrom(existing, options, bindingsSoFar);
				}
			}
			case WildcardType w ->
				this.isBindableFrom(w.capture(), options, bindingsSoFar);
			case CapturedType capturedType ->
				options.allowSubtypes()
					&& this.isBindableFrom(capturedType.upperBound(), options, bindingsSoFar);
			case NullType _ -> options.allowSubtypes();
			case ArrayType _, UnknownArrayType _ -> options.allowSubtypes() && rawClass().isAssignableFrom(Object[].class);
			case PrimitiveType _ -> false;
		};
	}

	private boolean argsAreBindable(BoundType candidate, BindableOptions options, Map<String, DataType> existingBindings) {
		for (int i = 0; i < this.bindings().size(); i++) {
			DataType patternArg = this.typeArgument(i);
			DataType candidateArg = candidate.parameterType(this.rawClass(), i);
			// When checking type arguments, subtypes are not allowed
			if (!patternArg.isBindableFrom(candidateArg, options.withAllowSubtypes(false), existingBindings)) {
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
