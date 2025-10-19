package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

import static java.lang.invoke.MethodType.methodType;

/**
 * Represents a JSON <em>value</em> as an arbitrary {@link #dataType}
 * converted to and from some intermediate {@code representation} by means of {@link MethodHandle}s.
 * Parsing proceeds by first parsing the representation type and then passing it to {@link #fromRepresentation};
 * JSON generation calls {@link #toRepresentation} and then generates JSON from the resulting value.
 * <p>
 * The {@link MethodHandle}s are treated like pure functions:
 * the number of calls is implementation-defined, and results may be memoized.
 * If they have side effects, the results will be implementation-dependent
 * (which would be appropriate in the case of, say, logging or instrumentation).
 *
 * @param representation     {@link JsonValueSpec} for the representation type
 * @param toRepresentation   {@link TypedHandle} that accepts an instance of {@link #dataType} and returns an instance of the representation type
 * @param fromRepresentation {@link TypedHandle} that accepts an instance of the representation type and returns an instance of {@link #dataType}
 */
public record RepresentAsSpec(
	JsonValueSpec representation,
	TypedHandle toRepresentation,
	TypedHandle fromRepresentation
) implements JsonValueSpec {
	public RepresentAsSpec {
		assert toRepresentation.parameterTypes().size() == 1;
		assert toRepresentation.parameterTypes().getFirst().isAssignableFrom(fromRepresentation.returnType());

		assert fromRepresentation.parameterTypes().size() == 1;
		assert fromRepresentation.parameterTypes().getFirst().isAssignableFrom(toRepresentation.returnType());

		assert toRepresentation.returnType().isAssignableFrom(representation.dataType());
		assert representation.dataType().isAssignableFrom(toRepresentation.returnType());
	}

	@Override
	public KnownType dataType() {
		return fromRepresentation().returnType();
	}

	/**
	 * Convenience method using {@link Function} instead of {@link TypedHandle}.
	 */
	public static <V,R> RepresentAsSpec as(
		JsonValueSpec representation,
		KnownType dataType,
		Function<V,R> toRepresentation,
		Function<R,V> fromRepresentation
	) {
		KnownType representationType = representation.dataType();
		MethodHandle toHandle = FUNCTION_APPLY.bindTo(toRepresentation).asType(
			methodType(representationType.rawClass(), dataType.rawClass()));
		MethodHandle fromHandle = FUNCTION_APPLY.bindTo(fromRepresentation).asType(
			methodType(dataType.rawClass(), representationType.rawClass()));
		return new RepresentAsSpec(
			representation,
			new TypedHandle(toHandle, representationType, List.of(dataType)),
			new TypedHandle(fromHandle, dataType, List.of(representationType))
		);
	}

	/**
	 * Specialized version of {@link #as} for representing as an int without boxing.
	 * This would be useful for enums represented by their ordinals, for example.
	 * <p>
	 * (Other primitives could be supported similarly if a need arises.)
	 */
	public static <V> RepresentAsSpec asInt(
		KnownType dataType,
		ToIntFunction<V> toRepresentation,
		IntFunction<V> fromRepresentation
	) {
		KnownType representationType = DataType.INT;
		MethodHandle toHandle = TO_INT_FUNCTION_APPLY.bindTo(toRepresentation).asType(
			methodType(representationType.rawClass(), dataType.rawClass()));
		MethodHandle fromHandle = INT_FUNCTION_APPLY.bindTo(fromRepresentation).asType(
			methodType(dataType.rawClass(), representationType.rawClass()));
		return new RepresentAsSpec(
			new PrimitiveNumberNode(int.class),
			new TypedHandle(toHandle, representationType, List.of(dataType)),
			new TypedHandle(fromHandle, dataType, List.of(representationType))
		);
	}

	private static final MethodHandle FUNCTION_APPLY;
	private static final MethodHandle TO_INT_FUNCTION_APPLY;
	private static final MethodHandle INT_FUNCTION_APPLY;

	static {
		try {
			FUNCTION_APPLY = MethodHandles.lookup().unreflect(Function.class.getMethod("apply", Object.class));
			TO_INT_FUNCTION_APPLY = MethodHandles.lookup().unreflect(ToIntFunction.class.getMethod("applyAsInt", Object.class));
			INT_FUNCTION_APPLY = MethodHandles.lookup().unreflect(IntFunction.class.getMethod("apply", int.class));
		} catch (IllegalAccessException | NoSuchMethodException e) {
			throw new IllegalStateException("Unexpected error looking up Function.apply", e);
		}
	}
}
