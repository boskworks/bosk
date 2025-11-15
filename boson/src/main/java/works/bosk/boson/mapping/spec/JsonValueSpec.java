package works.bosk.boson.mapping.spec;

import java.util.Map;
import works.bosk.boson.types.DataType;

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
{
	/**
	 * Subtypes are encouraged to redefine the return type covariantly.
	 *
	 * @return a new {@link JsonValueSpec} of the same type as {@code this} with the same semantics,
	 * but with type variables in any {@link DataType}s substituted for the given types.
	 */
	JsonValueSpec specialize(Map<String, DataType> actualArguments);

}
