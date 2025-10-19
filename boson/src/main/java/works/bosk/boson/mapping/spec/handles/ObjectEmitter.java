package works.bosk.boson.mapping.spec.handles;

import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.types.DataType.BOOLEAN;

/**
 * Describes how a {@link UniformMapNode} is to be serialized.
 * Like {@link Iterable}, but supports primitive values, and key-value pairs.
 * <p>
 * The handles have internal consistency rules enforced by the constructor.
 * There are some additional rules the caller should follow:
 * <ul>
 *     <li>
 *         {@code start} must accept value of {@link UniformMapNode}'s type
 *     </li>
 *     <li>
 *         {@code getKey} must return a value of {@link UniformMapNode#keyNode() keyNode}'s type
 *     </li>
 *     <li>
 *         {@code getValue} must return a value of {@link UniformMapNode#valueNode() valueNode}'s type
 *     </li>
 * </ul>
 *
 * The "intermediate" values returned by {@code start} and {@code next} can be primitives.
 *
 * @param start     given the object being emitted, returns an iterator-like value
 * @param hasNext   given the iterator, returns {@code true} if there are more members to emit
 * @param next      given the iterator, returns a value representing the next member to emit
 * @param getKey    given the member, returns a value representing its name
 * @param getValue  given the member, returns its value
 */
public record ObjectEmitter(
	TypedHandle start,
	TypedHandle hasNext,
	TypedHandle next,
	TypedHandle getKey,
	TypedHandle getValue
) {
	public ObjectEmitter {
		assert start.parameterTypes().size() == 1;

		assert hasNext.parameterTypes().size() == 1;
		assert hasNext.parameterTypes().getFirst().isAssignableFrom(start.returnType());
		assert hasNext.returnType().equals(BOOLEAN);

		assert next.parameterTypes().size() == 1;
		assert next.parameterTypes().getFirst().isAssignableFrom(start.returnType());

		assert getKey.parameterTypes().size() == 1;
		assert getKey.parameterTypes().getFirst().isAssignableFrom(next.returnType());

		assert getValue.parameterTypes().size() == 1;
		assert getValue.parameterTypes().getFirst().isAssignableFrom(next.returnType());
	}

	/**
	 * @return static type of the value being emitted
	 */
	public KnownType dataType() {
		return start.parameterTypes().getFirst();
	}

	@Override
	public String toString() {
		return dataType() + "->{"+getKey.returnType()+":"+getValue.returnType()+"}";
	}
}
