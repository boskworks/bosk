package works.bosk.boson.mapping.spec.handles;

import java.util.stream.Gatherer;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.types.DataType.VOID;

/**
 * Describes how an {@link ArrayNode} is to be deserialized.
 * Like {@link Gatherer} but supports primitives.
 *
 * <p>
 * The handles have internal consistency rules enforced by the constructor.
 * There are some additional rules the caller should follow:
 * <ul>
 *   <li>
 *     {@code integrator} must accept a value of the {@link ArrayNode#elementNode() elementNode}'s type
 *   </li>
 *   <li>
 *     {@code finisher} must return a value of the {@link ArrayNode}'s type
 *   </li>
 * </ul>
 *
 * The "intermediate" value by {@code creator} can be a primitive.
 * <p>
 * If the integrator returns void, the accumulator is assumed to be updated in-place,
 * and the one returned by the creator will continue to be used.
 * Otherwise, the integrator returns the updated accumulator.
 *
 * @param creator    produces the initial accumulator value
 * @param integrator given the accumulator and an element, returns an updated accumulator value
 * @param finisher   given the accumulator, returns the final result
 */
public record ArrayAccumulator(
	TypedHandle creator,
	TypedHandle integrator,
	TypedHandle finisher
) {
	public ArrayAccumulator {
		// TODO: injection
		assert creator.parameterTypes().isEmpty();

		assert integrator.parameterTypes().size() == 2;
		assert integrator.parameterTypes().getFirst().isAssignableFrom(creator.returnType());
		assert integrator.returnType() == VOID || integrator.returnType().equals(creator.returnType());

		assert finisher.parameterTypes().size() == 1;
		assert finisher.parameterTypes().getFirst().isAssignableFrom(creator.returnType());
	}

	public KnownType elementType() {
		return integrator.parameterTypes().getLast();
	}

	public KnownType resultType() {
		return finisher.returnType();
	}

	@Override
	public String toString() {
		return "[" + elementType() + "]->" + resultType();
	}
}
