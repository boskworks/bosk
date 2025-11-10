package works.bosk.boson.mapping.spec;

import java.util.Map;
import works.bosk.boson.types.DataType;

/**
 * A node corresponding to a JSON <em>value</em> that is not an <em>array</em> or <em>object</em>.
 *
 * @see ArraySpec
 * @see ObjectSpec
 */
public sealed interface ScalarSpec extends JsonValueSpec permits
	BigNumberNode,
	BooleanNode,
	BoxedPrimitiveSpec,
	EnumByNameNode,
	PrimitiveNumberNode,
	StringNode
{
	@Override
	default ScalarSpec substitute(Map<String, DataType> actualArguments) {
		return this;
	}
}
