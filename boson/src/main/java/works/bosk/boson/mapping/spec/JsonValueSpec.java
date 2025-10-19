package works.bosk.boson.mapping.spec;

/**
 * Describes how a particular JSON <em>value</em> corresponds to an in-memory representation.
 * <p>
 * SpecNodes generally do not need to deal with nulls;
 * see {@link MaybeNullSpec}.
 * <p>
 * The {@link Object#toString() toString} method should return
 * a compact human-readable representation of the spec
 * suitable for log messages and debugging.
 */
public sealed interface JsonValueSpec extends SpecNode permits
	ArraySpec,
	ParseCallbackSpec,
	MaybeNullSpec,
	ObjectSpec,
	RepresentAsSpec,
	ScalarSpec,
	TypeRefNode
{ }
