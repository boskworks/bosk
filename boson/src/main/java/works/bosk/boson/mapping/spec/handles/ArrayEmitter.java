package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
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

	/**
	 * @param <A> the in-memory representation of the array
	 * @param <I> the iterator type
	 * @param <E> the element type
	 */
	public interface Wrangler<A, I, E> {
		I start(A representation);
		boolean hasNext(I iterator);
		E next(I iterator);
	}

	public static ArrayEmitter of(Wrangler<?,?,?> wrangler) {
		BoundType wranglerType = (BoundType) DataType.of(wrangler.getClass());
		KnownType arrayType = (KnownType) wranglerType.parameterType(Wrangler.class, 0);
		KnownType iteratorType = (KnownType) wranglerType.parameterType(Wrangler.class, 1);
		KnownType elementType = (KnownType) wranglerType.parameterType(Wrangler.class, 2);

		return new ArrayEmitter(
			new TypedHandle(
				WRANGLER_START.bindTo(wrangler)
					.asType(MethodType.methodType(
						iteratorType.rawClass(),
						arrayType.rawClass()
					)),
				iteratorType, List.of(arrayType)
			),
			new TypedHandle(
				WRANGLER_HAS_NEXT.bindTo(wrangler)
					.asType(MethodType.methodType(
						boolean.class,
						iteratorType.rawClass()
					)),
				BOOLEAN, List.of(iteratorType)
			),
			new TypedHandle(
				WRANGLER_NEXT.bindTo(wrangler)
					.asType(MethodType.methodType(
						elementType.rawClass(),
						iteratorType.rawClass()
					)),
				elementType, List.of(iteratorType)
			)
		);
	}

	private static final MethodHandle WRANGLER_START;
	private static final MethodHandle WRANGLER_HAS_NEXT;
	private static final MethodHandle WRANGLER_NEXT;

	static {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		try {
			WRANGLER_START = lookup.findVirtual(
				Wrangler.class,
				"start",
				MethodType.methodType(Object.class, Object.class)
			);
			WRANGLER_HAS_NEXT = lookup.findVirtual(
				Wrangler.class,
				"hasNext",
				MethodType.methodType(boolean.class, Object.class)
			);
			WRANGLER_NEXT = lookup.findVirtual(
				Wrangler.class,
				"next",
				MethodType.methodType(Object.class, Object.class)
			);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
