package works.bosk;

import java.util.Objects;

/**
 * A {@link StateTreeNode} representing one of several possible {@link TaggedUnionCase}s.
 */
public record TaggedUnion<V extends TaggedUnionCase>(V value) {
	public TaggedUnion {
		Objects.requireNonNull(value);
	}

	public static <VV extends TaggedUnionCase> TaggedUnion<VV> of(VV value) {
		return new TaggedUnion<>(value);
	}
}
