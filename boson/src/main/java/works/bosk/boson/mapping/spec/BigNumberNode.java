package works.bosk.boson.mapping.spec;

import java.math.BigDecimal;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

/**
 * Represents a JSON number as a {@link Number}
 * that preserves the full value of the number.
 */
public record BigNumberNode(
	Class<? extends Number> numberClass
) implements ScalarSpec {
	public BigNumberNode {
		assert numberClass == BigDecimal.class: "Only BigDecimal is supported";
	}

	@Override
	public String toString() {
		return "BigNumber:" + numberClass.getSimpleName();
	}

	public KnownType dataType() {
		return DataType.known(numberClass());
	}

	@Override
	public String briefIdentifier() {
		return numberClass.getSimpleName();
	}
}
