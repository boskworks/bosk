package works.bosk;

/**
 * An object that can participate in a {@link TaggedUnion}
 * via a {@link works.bosk.annotations.TaggedUnionCaseMap @TaggedUnionCaseMap}.
 */
public interface TaggedUnionCase extends StateTreeNode {
	/**
	 * @return the tag identifying this case, which must be a key in the case map of the
	 * union's case supertype
	 */
	String tag(); // TODO: Should be an Identifier
}
