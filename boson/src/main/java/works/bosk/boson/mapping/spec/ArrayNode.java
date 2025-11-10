package works.bosk.boson.mapping.spec;

import java.util.Map;
import works.bosk.boson.mapping.spec.handles.ArrayAccumulator;
import works.bosk.boson.mapping.spec.handles.ArrayEmitter;
import works.bosk.boson.types.DataType;

public record ArrayNode(
	JsonValueSpec elementNode,
	ArrayAccumulator accumulator,
	ArrayEmitter emitter
) implements ArraySpec {
	public ArrayNode {
		// We parse from the bottom up. elementNode will produce an element,
		// and the accumulator must accept that element.
		assert accumulator.elementType().isAssignableFrom(elementNode.dataType()):
			"accumulator must accept elements of type " + elementNode.dataType();

		// We generate from the top down. The emitter iterates over elements,
		// and the elementNode must be capable of emitting those elements.
		assert elementNode.dataType().isAssignableFrom(emitter.elementType()):
			"emitter must supply elements of type " + elementNode.dataType();
	}

	@Override
	public DataType dataType() {
		return accumulator.resultType();
	}

	@Override
	public String briefIdentifier() {
		return elementNode().briefIdentifier() + "_Array";
	}

	@Override
	public ArrayNode substitute(Map<String, DataType> actualArguments) {
		return new ArrayNode(
			elementNode.substitute(actualArguments),
			accumulator.substitute(actualArguments),
			emitter.substitute(actualArguments)
		);
	}
}
