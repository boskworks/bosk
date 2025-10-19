package works.bosk.boson.mapping.spec.handles;

import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.types.DataType.BOOLEAN;

/**
 * Describes how an {@link ArrayNode} is to be serialized.
 * Like {@link Iterable} but supports primitives.
 *
 * <p>
 * The handles have internal consistency rules enforced by the constructor.
 * There are some additional rules the caller should follow:
 * <ul>
 *     <li>
 *         {@code start} must accept value of the {@link ArrayNode}'s type
 *     </li>
 *     <li>
 *         {@code next} must return a value of the {@link ArrayNode#elementNode() elementNode}'s type
 *     </li>
 * </ul>
 *
 * The "intermediate" value returned by {@code start} can be primitives.
 *
 * @param start     given the object being emitted, returns an iterator-like value
 * @param hasNext   given the iterator, returns {@code true} if there are more elements to emit
 * @param next      given the iterator, returns a value representing the next element to emit
 */
public record ArrayEmitter(
	TypedHandle start,
	TypedHandle hasNext,
	TypedHandle next
) {
	public ArrayEmitter {
		assert start.parameterTypes().size() == 1;

		assert hasNext.parameterTypes().size() == 1;
		assert hasNext.parameterTypes().getFirst().isAssignableFrom(start.returnType());
		assert hasNext.returnType().equals(BOOLEAN);

		assert next.parameterTypes().size() == 1;
		assert next.parameterTypes().getFirst().isAssignableFrom(start.returnType());
	}

	/**
	 * @return static type of the value being emitted
	 */
	public KnownType dataType() {
		return start.parameterTypes().getFirst();
	}

	/**
	 * @return static type of the elements
	 */
	public KnownType elementType() {
		return next.returnType();
	}

	@Override
	public String toString() {
		return dataType() + "->[" + next.returnType() + "]";
	}
}
