package works.bosk.boson.mapping.spec;

import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

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

	public KnownType dataType() {
		return supplier().returnType();
	}
}
