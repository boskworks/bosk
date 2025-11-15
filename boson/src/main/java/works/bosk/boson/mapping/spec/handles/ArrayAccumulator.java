package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.stream.Gatherer;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;

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

	public DataType elementType() {
		return integrator.parameterTypes().getLast();
	}

	public DataType resultType() {
		return finisher.returnType();
	}

	public ArrayAccumulator substitute(Map<String, DataType> actualArguments) {
		return new ArrayAccumulator(
			creator.substitute(actualArguments),
			integrator.substitute(actualArguments),
			finisher.substitute(actualArguments)
		);
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
	public interface Wrangler<A,E,T> {
		A create();
		A integrate(A accumulator, E element);
		T finish(A accumulator);
	}

	public static ArrayAccumulator from(Wrangler<?,?,?> wrangler) {
		BoundType wranglerType = (BoundType) DataType.of(wrangler.getClass());
		var accumulatorType = wranglerType.parameterType(Wrangler.class, 0);
		var elementType = wranglerType.parameterType(Wrangler.class, 1);
		var resultType = wranglerType.parameterType(Wrangler.class, 2);

		return new ArrayAccumulator(
			new TypedHandle(
				WRANGLER_CREATE.bindTo(wrangler)
					.asType(MethodType.methodType(accumulatorType.leastUpperBoundClass())),
				accumulatorType, List.of()
			),
			new TypedHandle(
				WRANGLER_INTEGRATE.bindTo(wrangler)
					.asType(MethodType.methodType(
						accumulatorType.leastUpperBoundClass(),
						accumulatorType.leastUpperBoundClass(),
						elementType.leastUpperBoundClass()
					)),
				accumulatorType, List.of(accumulatorType, elementType)
			),
			new TypedHandle(
				WRANGLER_FINISH.bindTo(wrangler)
					.asType(MethodType.methodType(
						resultType.leastUpperBoundClass(),
						accumulatorType.leastUpperBoundClass()
					)),
				resultType, List.of(accumulatorType)
			)
		);
	}

	private static final MethodHandle WRANGLER_CREATE;
	private static final MethodHandle WRANGLER_INTEGRATE;
	private static final MethodHandle WRANGLER_FINISH;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			WRANGLER_CREATE = lookup.findVirtual(
				Wrangler.class,
				"create",
				MethodType.methodType(Object.class)
			);
			WRANGLER_INTEGRATE = lookup.findVirtual(
				Wrangler.class,
				"integrate",
				MethodType.methodType(Object.class, Object.class, Object.class)
			);
			WRANGLER_FINISH = lookup.findVirtual(
				Wrangler.class,
				"finish",
				MethodType.methodType(Object.class, Object.class)
			);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
