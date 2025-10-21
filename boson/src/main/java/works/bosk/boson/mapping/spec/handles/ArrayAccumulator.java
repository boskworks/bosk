package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.stream.Gatherer;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
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

	/**
	 * @param <A> the accumulator type
	 * @param <E> the element type
	 * @param <T> the result type
	 */
	public interface Handler<A,E,T> {
		A create();
		A integrate(A accumulator, E element);
		T finish(A accumulator);
	}

	public static ArrayAccumulator of(Handler<?,?,?> handler) {
		BoundType handlerType = (BoundType) DataType.of(handler.getClass());
		KnownType accumulatorType = (KnownType) handlerType.parameterType(Handler.class, 0);
		KnownType elementType = (KnownType) handlerType.parameterType(Handler.class, 1);
		KnownType resultType = (KnownType) handlerType.parameterType(Handler.class, 2);

		return new ArrayAccumulator(
			new TypedHandle(
				HANDLER_CREATE.bindTo(handler)
					.asType(MethodType.methodType(accumulatorType.rawClass())),
				accumulatorType, List.of()
			),
			new TypedHandle(
				HANDLER_INTEGRATE.bindTo(handler)
					.asType(MethodType.methodType(
						accumulatorType.rawClass(),
						accumulatorType.rawClass(),
						elementType.rawClass()
					)),
				accumulatorType, List.of(accumulatorType, elementType)
			),
			new TypedHandle(
				HANDLER_FINISH.bindTo(handler)
					.asType(MethodType.methodType(
						resultType.rawClass(),
						accumulatorType.rawClass()
					)),
				resultType, List.of(accumulatorType)
			)
		);
	}

	private static final MethodHandle HANDLER_CREATE;
	private static final MethodHandle HANDLER_INTEGRATE;
	private static final MethodHandle HANDLER_FINISH;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			HANDLER_CREATE = lookup.findVirtual(
				Handler.class,
				"create",
				MethodType.methodType(Object.class)
			);
			HANDLER_INTEGRATE = lookup.findVirtual(
				Handler.class,
				"integrate",
				MethodType.methodType(Object.class, Object.class, Object.class)
			);
			HANDLER_FINISH = lookup.findVirtual(
				Handler.class,
				"finish",
				MethodType.methodType(Object.class, Object.class)
			);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
