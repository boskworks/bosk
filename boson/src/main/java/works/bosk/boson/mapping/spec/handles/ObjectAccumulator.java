package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.stream.Gatherer;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;

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

	public DataType keyType() {
		return integrator.parameterTypes().get(1);
	}

	public DataType valueType() {
		return integrator.parameterTypes().get(2);
	}

	public DataType resultType() {
		return finisher.returnType();
	}

	public ObjectAccumulator substitute(Map<String, DataType> actualArguments) {
		return new ObjectAccumulator(
			creator.substitute(actualArguments),
			integrator.substitute(actualArguments),
			finisher.substitute(actualArguments)
		);
	}

	/**
	 * @param <T> the type of the value object representing the object in memory
	 * @param <A> the accumulator type
	 * @param <K> the map key type
	 * @param <V> the map value type
	 */
	public interface Wrangler<T,A,K,V> {
		A create();
		A integrate(A accumulator, K key, V value);
		T finish(A accumulator);
	}

	public static <T,A,K,V> ObjectAccumulator from(Wrangler<T,A,K,V> wrangler) {
		var wranglerType = (BoundType)DataType.of(wrangler.getClass());
		var resultType = wranglerType.parameterType(Wrangler.class, 0);
		var accumulatorType = wranglerType.parameterType(Wrangler.class, 1);
		var keyType = wranglerType.parameterType(Wrangler.class, 2);
		var valueType = wranglerType.parameterType(Wrangler.class, 3);

		return new ObjectAccumulator(
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
						keyType.leastUpperBoundClass(),
						valueType.leastUpperBoundClass()
					)),
				accumulatorType, List.of(accumulatorType, keyType, valueType)
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
				MethodType.methodType(Object.class, Object.class, Object.class, Object.class)
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

	@Override
	public String toString() {
		return "{" + keyType() + ":" + valueType() + "}->" + resultType();
	}
}
