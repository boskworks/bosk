package works.bosk.boson.mapping.spec;

import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;

/**
 * Represents a portion of the in-memory structure that is returned by {@code supplier}
 * and does not correspond * to any JSON text.
 * <p>
 * When parsing, {@code supplier} is called to produce the in-memory value.
 * When generating JSON, nothing is emitted.
 *
 * @param supplier
 */
public record ComputedSpec(
	TypedHandle supplier
) implements SpecNode {

	public ComputedSpec {
		assert supplier.parameterTypes().isEmpty(); // TODO: inject
		assert !supplier.returnType().equals(DataType.VOID);
	}

	@Override
	public DataType dataType() {
		return supplier().returnType();
	}

	@Override
	public String briefIdentifier() {
		return "Computed_" + dataType().leastUpperBoundClass().getSimpleName();
	}
}
