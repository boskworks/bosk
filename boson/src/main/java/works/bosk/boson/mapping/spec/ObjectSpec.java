package works.bosk.boson.mapping.spec;

/**
 * A node corresponding to a JSON <em>object</em>
 *
 * @see ScalarSpec
 * @see ArraySpec
 */
public sealed interface ObjectSpec extends JsonValueSpec permits
	FixedMapNode,
	UniformMapNode
{
}
