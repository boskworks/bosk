package works.bosk.boson.mapping.spec;

/**
 * A node corresponding to a JSON <em>array</em>
 *
 * @see ScalarSpec
 * @see ObjectSpec
 */
public sealed interface ArraySpec extends JsonValueSpec permits ArrayNode {
}
