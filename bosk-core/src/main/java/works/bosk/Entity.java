package works.bosk;

/**
 * A {@link StateTreeNode} representing a thing with its own {@link #id() identity}
 * (as opposed to a mere value) that can reside in a {@link Catalog} and be referenced
 * by {@link Listing} and {@link SideTable}.
 *
 * <p>
 * <em>Note</em>: If you think you want to create a <code>Set</code> of your entity objects,
 * or use them as <code>Map</code> keys, consider using {@link Reference}s as
 * keys instead. In the Bosk system, {@link Reference}s are a reliable way to
 * indicate the identity of an object, because an object's identity is defined
 * by its location in the document tree. (There is no notion of "moving" an
 * object in a Bosk while retaining its identity.)
 *
 * @see Catalog
 * @author pdoyle
 */
public interface Entity extends StateTreeNode {
	/**
	 * @return an {@link Identifier} that uniquely identifies this {@link
	 * Entity} within its containing {@link Catalog}.  Note that this is not
	 * guaranteed unique outside the scope of the {@link Catalog}.
	 */
	Identifier id();
}
