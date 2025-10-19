package works.bosk.boson.mapping.spec;

import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.mapping.spec.PrimitiveNumberNode.PRIMITIVE_NUMBER_CLASSES;

public record BoxedPrimitiveSpec(PrimitiveNumberNode child) implements ScalarSpec {
	@Override
	public String toString() {
		return "Boxed(" + child + ")";
	}

	@Override
	public KnownType dataType() {
		return DataType.known(targetClass());
	}

	public Class<? extends Number> targetClass() {
		return PRIMITIVE_NUMBER_CLASSES.get(child.targetClass());
	}
}
