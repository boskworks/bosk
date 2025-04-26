package works.bosk;

/**
 * One vertex of the object tree maintained by a {@link Bosk}.
 *
 * <p>
 * Essentially a marker interface indicating that an object is a willing
 * participant in the Boskiverse.  Various default behaviours (like
 * deserialization) will work differently from other less cooperative Java objects.
 * Useful as a generic type bound for APIs intended to operate on bosk state objects.
 *
 * @author pdoyle
 */
public interface StateTreeNode {

}
