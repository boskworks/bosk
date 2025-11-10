package works.bosk.boson.types;

import java.lang.reflect.Type;
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
	public TypeVariable(String name, Type... bounds) {
		this(name, List.of(bounds));
	}

	public static TypeVariable unbounded(String name) {
		return new TypeVariable(name, List.of());
	}

	@Override
	public String toString() {
		if (bounds.isEmpty()) {
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
	public boolean isAssignableFrom(DataType other) {
		// For type variables, isAssignableFrom is stricter
		// than isBindableFrom:
		// for T t = x, x must either be T itself or a provable subtype of T
		if (this.equals(other)) {
			return true;
		}

		if (other instanceof TypeVariable otherTv) {
			// JLS 5.1.6.1
			// - case 4: S is a type variable, and a narrowing reference conversion exists from the upper bound of S to T.
			// - case 6: S is an intersection type S1 & ... & Sn, and for all i (1 ≤ i ≤ n), either a widening reference conversion or a narrowing reference conversion exists from Si to T.
			// This means T must be assignable from all bounds of S.
			//
			// Intuitively it seems like any bound would be enough--
			// I'm not sure why the JLS requires all bounds--
			// but it's academic for type variables anyway,
			// because 4.4 disallows type variables
			// from participating in intersection types,
			// so if otherTv is not a singleton, it's not going to match.
			//
 			// However, allMatch returns true for an empty stream,
			// which is wrong, so we use anyMatch instead.
			//
			return otherTv.bounds().stream().anyMatch(t ->
				this.isAssignableFrom(DataType.of(t)));
		}

		// Otherwise, a type variable is only assignable from itself
		return false;
	}

	@Override
	public boolean isBindableFrom(DataType other) {
		return bounds.stream().allMatch(t ->
			DataType.of(t).isAssignableFrom(other));
	}

	@Override
	public Class<?> leastUpperBoundClass() {
		return switch (bounds.size()) {
			case 0 -> Object.class;
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
		// This is where the rubber meets the road:
		// we're binding this variable to `other`.
		return Map.of(name, other);
	}

	@Override
	public boolean hasWildcards() {
		// The type bounds can contain wildcards, but the important thing is that
		// if this type variable were substituted with a wildcard-free type, the
		// result would be wildcard-free regardless of the bounds, so we return false here.
		return false;
	}
}
