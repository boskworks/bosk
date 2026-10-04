package works.bosk;

/**
 * An object that can participate in a {@link TaggedUnion}
 * via a {@link works.bosk.annotations.TaggedUnionCaseMap @TaggedUnionCaseMap}.
 */
public interface TaggedUnionCase extends StateTreeNode {
	String tag(); // TODO: Should be an Identifier
}
