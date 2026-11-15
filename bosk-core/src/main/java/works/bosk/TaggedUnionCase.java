package works.bosk;

/**
 * An object that can participate in a {@link TaggedUnion}
 * via a {@link works.bosk.annotations.TaggedUnionCaseMap @TaggedUnionCaseMap}.
 * <p>
 * A case is an ordinary {@link StateTreeNode}; its contents can be updated through
 * a path that includes the case's tag. The tag itself is part of the union's
 * identity, so changing it is done by replacing the union.
 */
public interface TaggedUnionCase extends StateTreeNode {
	/**
	 * @return the tag identifying this case, which must be a key in the case map of the
	 * union's case supertype
	 */
	String tag(); // TODO: Should be an Identifier
}
