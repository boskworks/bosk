package works.bosk.boson.mapping.spec.handles;

import java.util.stream.Gatherer;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.types.DataType.VOID;

/**
 * Describes how a {@link UniformMapNode} is to be deserialized.
 * Like {@link Gatherer} but supports primitives and key-value pairs.
 *
 * <p>
 * The handles have internal consistency rules enforced by the constructor.
 * There are some additional rules the caller should follow:
 * <ul>
 *   <li>
 *     {@code integrator} must accept values of the {@link UniformMapNode#keyNode() keyNode}'s and {@link UniformMapNode#valueNode() valueNode}'s types
 *   </li>
 *   <li>
 *     {@code finisher} must return a value of the {@link UniformMapNode}'s type
 *   </li>
 * </ul>
 *
 * The "intermediate" value produced by {@code creator} can be a primitive.
 * <p>
 * If the integrator returns void, the accumulator is assumed to be updated in-place,
 * and the one returned by the creator will continue to be used.
 * Otherwise, the integrator returns the updated accumulator.
 *
 * @param creator    produces the initial accumulator value
 * @param integrator given the accumulator, a key, and a value, updates the accumulator, and returns it (or void)
 * @param finisher   given the accumulator, returns the final result
 */
public record ObjectAccumulator(
	TypedHandle creator,
	TypedHandle integrator,
	TypedHandle finisher
) {
	public ObjectAccumulator {
		// TODO: injection
		assert creator.parameterTypes().isEmpty();

		assert integrator.parameterTypes().size() == 3;
		assert integrator.parameterTypes().getFirst().isAssignableFrom(creator.returnType());
		assert integrator.returnType() == VOID || integrator.returnType().equals(creator.returnType());

		assert finisher.parameterTypes().size() == 1;
		assert finisher.parameterTypes().getFirst().isAssignableFrom(creator.returnType());
	}

	public KnownType keyType() {
		return integrator.parameterTypes().get(1);
	}

	public KnownType valueType() {
		return integrator.parameterTypes().get(2);
	}

	public KnownType resultType() {
		return finisher.returnType();
	}

	@Override
	public String toString() {
		return "{" + keyType() + ":" + valueType() + "}->" + resultType();
	}
}
