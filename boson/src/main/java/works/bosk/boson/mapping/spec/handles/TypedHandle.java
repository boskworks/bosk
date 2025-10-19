package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

public record TypedHandle(
	MethodHandle handle,
	KnownType returnType,
	List<KnownType> parameterTypes
) {
	public TypedHandle {
		requireNonNull(returnType);
		requireNonNull(parameterTypes);
		requireNonNull(handle);
		MethodType equivalentMethodType = equivalentMethodType(returnType, parameterTypes);
		if (!handle.type().equals(equivalentMethodType)) {
			System.err.println("OH NOES");
		}
		assert handle.type().equals(equivalentMethodType(returnType, parameterTypes))
			: "Method handle type " + handle.type() + " does not match expected type " + equivalentMethodType(returnType, parameterTypes);
	}

	private static MethodType equivalentMethodType(KnownType returnType, List<KnownType> parameterTypes) {
		return methodType(
			returnType.rawClass(),
			parameterTypes.stream().map(KnownType::rawClass).toArray(Class<?>[]::new)
		);
	}

	public Object invoke(Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (ClassCastException | WrongMethodTypeException e) {
			throw new IllegalStateException("Unexpected type error invoking method handle " + this, e);
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	public TypedHandle curry(int parameterIndex, Object value) {
		if (parameterIndex < 0 || parameterIndex >= parameterTypes.size()) {
			throw new IndexOutOfBoundsException("No parameter " + parameterIndex + " in " + this);
		}
		MethodHandle newHandle = insertArguments(handle, parameterIndex, value);
		List<KnownType> newParameterTypes = new ArrayList<>(parameterTypes);
		newParameterTypes.remove(parameterIndex);
		return new TypedHandle(newHandle, returnType, List.copyOf(newParameterTypes));
	}

	public TypedHandle bind(int parameterIndex, TypedHandle parameter) {
		// Drop the formality: cast the parameter handle to the expected type
		MethodHandle parameterHandle = parameter.handle()
			.asType(parameter.handle.type()
				.changeReturnType(handle.type().parameterType(parameterIndex)));
		var resultHandle = MethodHandles.collectArguments(handle, parameterIndex, parameterHandle);
		List<KnownType> resultParameterTypes = new ArrayList<>(parameterTypes());
		resultParameterTypes.remove(parameterIndex);
		resultParameterTypes.addAll(parameter.parameterTypes());
		return new TypedHandle(resultHandle, returnType, resultParameterTypes);
	}

	@Override
	public String toString() {
		return "(" + String.join(", ", parameterTypes.stream().map(KnownType::toString).toList()) + ")->" + returnType;
	}

	public static <R> TypedHandle ofSupplier(KnownType returnType, Supplier<R> supplier) {
		return new TypedHandle(
			SUPPLIER_GET
				.bindTo(supplier)
				.asType(methodType(returnType.rawClass())),
			returnType,
			List.of()
		);
	}

	public static <T> TypedHandle ofConsumer(KnownType argType, Consumer<T> consumer) {
		return new TypedHandle(
			CONSUMER_ACCEPT
				.bindTo(consumer)
				.asType(methodType(void.class, argType.rawClass())),
			DataType.VOID,
			List.of(argType)
		);
	}

	public static <T1,T2> TypedHandle ofBiConsumer(KnownType argType1, KnownType argType2, BiConsumer<T1,T2> biConsumer) {
		return new TypedHandle(
			BICONSUMER_ACCEPT
				.bindTo(biConsumer)
				.asType(methodType(void.class, argType1.rawClass(), argType2.rawClass())),
			DataType.VOID,
			List.of(argType1, argType2)
		);
	}

	public static <T> TypedHandle ofCallable(KnownType argType, Callable<T> callable) {
		return new TypedHandle(
			CALLABLE_CALL
				.bindTo(callable)
				.asType(methodType(void.class, argType.rawClass())),
			DataType.VOID,
			List.of(argType)
		);
	}

	public static <T,R> TypedHandle ofFunction(KnownType argType, KnownType returnType, Function<T,R> function) {
		return new TypedHandle(
			FUNCTION_APPLY
				.bindTo(function)
				.asType(methodType(returnType.rawClass(), argType.rawClass())),
			returnType,
			List.of(argType)
		);
	}

	public static <T> TypedHandle ofPredicate(KnownType argType, Predicate<T> predicate) {
		return new TypedHandle(
			PREDICATE_TEST
				.bindTo(predicate)
				.asType(methodType(boolean.class, argType.rawClass())),
			DataType.BOOLEAN,
			List.of(argType)
		);
	}

	public static TypedHandle ofComponentAccessor(RecordComponent rc) {
		MethodHandle handle;
		try {
			handle = MethodHandles.lookup().unreflect(rc.getAccessor());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		KnownType componentType = DataType.known(rc.getType());
		KnownType recordType = DataType.known(rc.getDeclaringRecord());
		return new TypedHandle(handle, componentType, List.of(recordType));
	}

	private static final MethodHandle FUNCTION_APPLY;
	private static final MethodHandle PREDICATE_TEST;
	private static final MethodHandle SUPPLIER_GET;
	private static final MethodHandle CONSUMER_ACCEPT;
	private static final MethodHandle BICONSUMER_ACCEPT;
	private static final MethodHandle CALLABLE_CALL;

	static {
		try {
			FUNCTION_APPLY = MethodHandles.lookup().findVirtual(Function.class, "apply", methodType(Object.class, Object.class));
			PREDICATE_TEST = MethodHandles.lookup().findVirtual(Predicate.class, "test", methodType(boolean.class, Object.class));
			SUPPLIER_GET = MethodHandles.lookup().findVirtual(Supplier.class, "get", methodType(Object.class));
			CONSUMER_ACCEPT = MethodHandles.lookup().findVirtual(java.util.function.Consumer.class, "accept", methodType(void.class, Object.class));
			BICONSUMER_ACCEPT = MethodHandles.lookup().findVirtual(java.util.function.BiConsumer.class, "accept", methodType(void.class, Object.class, Object.class));
			CALLABLE_CALL = MethodHandles.lookup().findVirtual(Callable.class, "call", methodType(Object.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
