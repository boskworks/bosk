package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;

import static works.bosk.boson.types.DataType.BOOLEAN;
import static works.bosk.boson.types.DataType.LONG;

/**
 * Describes how a {@link UniformMapNode} is to be serialized.
 * Like {@link Iterable}, but supports primitive values, and key-value pairs.
 * <p>
 * The handles have internal consistency rules enforced by the constructor.
 * There are some additional rules the caller should follow:
 * <ul>
 *     <li>
 *         {@code start} must accept a value of {@link UniformMapNode}'s type.
 *     </li>
 *     <li>
 *         {@code getKey} must return a value of {@link UniformMapNode#keyNode() keyNode}'s type.
 *     </li>
 *     <li>
 *         {@code getValue} must return a value of {@link UniformMapNode#valueNode() valueNode}'s type.
 *     </li>
 * </ul>
 *
 * For generality and efficiency, two forms of iteration are supported.
 * The first form uses a mutable iterator object, in the style of {@link Iterator},
 * and operates according to this pseudocode:
 *
 * <pre>
 * 	for (var iter = start(obj); hasNext(iter); ) {
 *		var member = next(iter);
 *		generateMember(getKey(member), getValue(member));
 *	}
 * </pre>
 *
 * ({@code generateMember} is a placeholder representing the logic that emits a member of a JSON object.)
 * <p>
 * The second form supports standard for-loop iteration, including immutable (even primitive)
 * induction variables, more like this pseudocode:
 *
 * <pre>
 * 	for (var iter = start(obj); hasNext(iter, obj); iter = next(iter, obj)) {
 *		generateMember(getKey(iter, obj), getValue(iter, obj));
 *	}
 * </pre>
 *
 * The two forms are distinguished by whether {@code next} accepts the original object as its second parameter:
 *
 * <ul>
 *     <li>
 *         If it does not, the emitter is of the first (mutable) form.
 *         In this case, {@code hasNext} and {@code next} have the usual semantics from {@link Iterator},
 *         and {@code getKey} and {@code getValue} accept the member object returned by {@code next}.
 *     </li>
 *     <li>
 *         If it does, the emitter is of the second (immutable) form.
 *         In this case, {@code hasNext}, {@code getKey}, and {@code getValue} may also
 *         optionally accept the original object as an extra parameter.
 *     </li>
 * </ul>
 *
 * These two forms also differ in that the {@code next} method is called before {@link #getKey} and {@link #getValue}
 * in the mutable-iterator form, but after them in the for-loop form.
 *
 * @param start     given the object being emitted, returns an iterator-like value
 * @param hasNext   given the iterator (and possibly the original object), returns {@code true} if there are more members to emit
 * @param next      given the iterator, returns a value representing the next member to emit;
 *                  or for for-loop iteration, given the iterator and the original object, returns the next iterator value,
 *                  with the prior iterator serving the role of the "member" object from which key and value are extracted
 * @param getKey    given the member (or iterator and possibly the original object), returns a value representing its name
 * @param getValue  given the member (or iterator and possibly the original object), returns its value
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

		DataType object = start.parameterTypes().getFirst();
		DataType iteratorType = start.returnType();
		if (next.parameterTypes().size() == 1) {
			// Mutable iterator form
			assert next.parameterTypes().getFirst().isAssignableFrom(iteratorType):
				"next parameter type " + next.parameterTypes().getFirst()
					+ " must be assignable from iterator type " + iteratorType;

			assert hasNext.parameterTypes().size() == 1;
			assert hasNext.parameterTypes().getFirst().isAssignableFrom(iteratorType):
				"hasNext parameter type " + hasNext.parameterTypes().getFirst()
					+ " must be assignable from iterator type " + iteratorType;
			assert hasNext.returnType().equals(BOOLEAN):
				"hasNext return type " + hasNext.returnType() + " must be boolean";

			assert getKey.parameterTypes().size() == 1;
			assert getKey.parameterTypes().getFirst().isAssignableFrom(next.returnType()):
				"getKey parameter type " + getKey.parameterTypes().getFirst()
					+ " must be assignable from member type " + next.returnType();

			assert getValue.parameterTypes().size() == 1;
			assert getValue.parameterTypes().getFirst().isAssignableFrom(next.returnType()):
				"getValue parameter type " + getValue.parameterTypes().getFirst()
					+ " must be assignable from member type " + next.returnType();
		} else {
			// For-loop form
			assert next.parameterTypes().size() == 2;
			assert next.parameterTypes().getFirst().isAssignableFrom(iteratorType):
				"next parameter type " + next.parameterTypes().getFirst()
					+ " must be assignable from iterator type " + iteratorType;
			assert next.parameterTypes().getLast().isAssignableFrom(object):
				"next second parameter type " + next.parameterTypes().getLast()
					+ " must be assignable from object type " + object;
			assert iteratorType.isAssignableFrom(next.returnType()):
				"next return type " + next.returnType()
					+ " must be assignable to iterator type " + iteratorType;

			assert hasNext.parameterTypes().size() <= 2;
			assert hasNext.parameterTypes().getFirst().isAssignableFrom(iteratorType):
				"hasNext parameter type " + hasNext.parameterTypes().getFirst()
					+ " must be assignable from iterator type " + iteratorType;
			if (hasNext.parameterTypes().size() == 2) {
				assert hasNext.parameterTypes().getLast().isAssignableFrom(object):
					"hasNext second parameter type " + hasNext.parameterTypes().getLast()
						+ " must be assignable from object type " + object;
			}
			assert hasNext.returnType().equals(BOOLEAN):
				"hasNext return type " + hasNext.returnType() + " must be boolean";

			assert getKey.parameterTypes().size() <= 2;
			assert getKey.parameterTypes().getFirst().isAssignableFrom(next.returnType()):
				"getKey parameter type " + getKey.parameterTypes().getFirst()
					+ " must be assignable from member type " + next.returnType();
			if (getKey.parameterTypes().size() == 2) {
				assert getKey.parameterTypes().getLast().isAssignableFrom(object):
					"getKey second parameter type " + getKey.parameterTypes().getLast()
						+ " must be assignable from object type " + object;
			}

			assert getValue.parameterTypes().size() <= 2;
			assert getValue.parameterTypes().getFirst().isAssignableFrom(next.returnType()):
				"getValue parameter type " + getValue.parameterTypes().getFirst()
					+ " must be assignable from member type " + next.returnType();
			if (getValue.parameterTypes().size() == 2) {
				assert getValue.parameterTypes().getLast().isAssignableFrom(object):
					"getValue second parameter type " + getValue.parameterTypes().getLast()
						+ " must be assignable from object type " + object;
			}
		}

	}

	/**
	 * @return static type of the value being emitted
	 */
	public DataType dataType() {
		return start.parameterTypes().getFirst();
	}

	public ObjectEmitter substitute(Map<String, DataType> actualArguments) {
		return new ObjectEmitter(
			start.substitute(actualArguments),
			hasNext.substitute(actualArguments),
			next.substitute(actualArguments),
			getKey.substitute(actualArguments),
			getValue.substitute(actualArguments)
		);
	}

	@Override
	public String toString() {
		return dataType() + "->{"+getKey.returnType()+":"+getValue.returnType()+"}";
	}

	public interface ForLoopWrangler<T, K, V> {
		long start(T obj);
		boolean hasNext(long iter, T obj);
		long next(long iter, T obj);
		K getKey(long iter, T obj);
		V getValue(long iter, T obj);
	}

	public static <T, K, V> ObjectEmitter forLoop(ForLoopWrangler<T, K, V> wrangler) {
		var wranglerType = (BoundType)DataType.of(wrangler.getClass());
		var objectType = wranglerType.parameterType(ForLoopWrangler.class, 0);
		var keyType = wranglerType.parameterType(ForLoopWrangler.class, 1);
		var valueType = wranglerType.parameterType(ForLoopWrangler.class, 2);

		return new ObjectEmitter(
			new TypedHandle(
				FOR_LOOP_WRANGLER_START
					.bindTo(wrangler)
					.asType(MethodType.methodType(long.class, objectType.leastUpperBoundClass())),
				LONG, List.of(objectType)
			),
			new TypedHandle(
				FOR_LOOP_WRANGLER_HAS_NEXT
					.bindTo(wrangler)
					.asType(MethodType.methodType(boolean.class, long.class, objectType.leastUpperBoundClass())),
				BOOLEAN, List.of(LONG, objectType)
			),
			new TypedHandle(
				FOR_LOOP_WRANGLER_NEXT
					.bindTo(wrangler)
					.asType(MethodType.methodType(long.class, long.class, objectType.leastUpperBoundClass())),
				LONG, List.of(LONG, objectType)
			),
			new TypedHandle(
				FOR_LOOP_WRANGLER_GET_KEY
					.bindTo(wrangler)
					.asType(MethodType.methodType(keyType.leastUpperBoundClass(), long.class, objectType.leastUpperBoundClass())),
				keyType, List.of(LONG, objectType)
			),
			new TypedHandle(
				FOR_LOOP_WRANGLER_GET_VALUE
					.bindTo(wrangler)
					.asType(MethodType.methodType(valueType.leastUpperBoundClass(), long.class, objectType.leastUpperBoundClass())),
				valueType, List.of(LONG, objectType)
			)
		);
	}

	private static final MethodHandle FOR_LOOP_WRANGLER_START;
	private static final MethodHandle FOR_LOOP_WRANGLER_HAS_NEXT;
	private static final MethodHandle FOR_LOOP_WRANGLER_NEXT;
	private static final MethodHandle FOR_LOOP_WRANGLER_GET_KEY;
	private static final MethodHandle FOR_LOOP_WRANGLER_GET_VALUE;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			FOR_LOOP_WRANGLER_START = lookup.findVirtual(
				ForLoopWrangler.class,
				"start",
				MethodType.methodType(long.class, Object.class)
			);
			FOR_LOOP_WRANGLER_HAS_NEXT = lookup.findVirtual(
				ForLoopWrangler.class,
				"hasNext",
				MethodType.methodType(boolean.class, long.class, Object.class)
			);
			FOR_LOOP_WRANGLER_NEXT = lookup.findVirtual(
				ForLoopWrangler.class,
				"next",
				MethodType.methodType(long.class, long.class, Object.class)
			);
			FOR_LOOP_WRANGLER_GET_KEY = lookup.findVirtual(
				ForLoopWrangler.class,
				"getKey",
				MethodType.methodType(Object.class, long.class, Object.class)
			);
			FOR_LOOP_WRANGLER_GET_VALUE = lookup.findVirtual(
				ForLoopWrangler.class,
				"getValue",
				MethodType.methodType(Object.class, long.class, Object.class)
			);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
