package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

import static java.lang.invoke.MethodType.methodType;
import static works.bosk.boson.types.DataType.BOOLEAN;
import static works.bosk.boson.types.DataType.OBJECT;

public final class TypedHandles {
	private TypedHandles(){}

	public static TypedHandle canonicalConstructor(Class<? extends Record> recordType, MethodHandles.Lookup lookup) {
		MethodHandle mh;
		try {
			mh = lookup.findConstructor(recordType, MethodType.methodType(void.class, Stream.of(recordType.getRecordComponents())
				.map(RecordComponent::getType).toArray(Class<?>[]::new)
			));
		} catch (NoSuchMethodException e) {
			throw new AssertionError("Canonical constructor must exist for " + recordType);
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Can't access canonical constructor of " + recordType);
		}
		KnownType recordDataType = DataType.known(recordType); // Records can have unknown types! But we don't support that here.
		return new TypedHandle(
			mh,
			recordDataType,
			Stream.of(recordType.getRecordComponents())
				.map(rc -> {
					var dt = DataType.of(rc.getType());
					if (dt instanceof KnownType kt) {
						return kt;
					} else {
						throw new IllegalArgumentException("Record component " + rc + " has unknown type " + dt);
					}
				})
				.toList()
		);
	}

	public static TypedHandle componentAccessor(RecordComponent rc, MethodHandles.Lookup lookup) {
		MethodHandle mh;
		try {
			mh = lookup.unreflect(rc.getAccessor());
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Can't access accessor " + rc.getAccessor() + " of " + rc.getDeclaringRecord());
		}
		KnownType componentType = DataType.known(rc.getType());
		KnownType recordType = DataType.known(rc.getDeclaringRecord());
		return new TypedHandle(mh, componentType, List.of(recordType));
	}

	public static TypedHandle notEquals() {
		return new TypedHandle(NOT_EQUALS_HANDLE, BOOLEAN, List.of(OBJECT, OBJECT));
	}

	public static TypedHandle notEquals(TypedHandle argument) {
		return notEquals().bind(0, argument);
	}

	private static boolean notEquals(Object a, Object b) {
		return !a.equals(b);
	}

	private static final MethodHandle NOT_EQUALS_HANDLE;

	static {
		try {
			NOT_EQUALS_HANDLE = MethodHandles.lookup().findStatic(
				TypedHandles.class,
				"notEquals",
				MethodType.methodType(boolean.class, Object.class, Object.class)
			);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static <T> TypedHandle constant(KnownType returnType, T value) {
		return new TypedHandle(
			MethodHandles.constant(returnType.rawClass(), value),
			returnType,
			List.of()
		);
	}

	public static <R> TypedHandle supplier(KnownType returnType, Supplier<R> supplier) {
		return new TypedHandle(
			SUPPLIER_GET
				.bindTo(supplier)
				.asType(methodType(returnType.rawClass())),
			returnType,
			List.of()
		);
	}

	public static <T> TypedHandle consumer(KnownType argType, Consumer<T> consumer) {
		return new TypedHandle(
			CONSUMER_ACCEPT
				.bindTo(consumer)
				.asType(methodType(void.class, argType.rawClass())),
			VOID,
			List.of(argType)
		);
	}

	public static <T1,T2> TypedHandle biConsumer(KnownType argType1, KnownType argType2, BiConsumer<T1,T2> biConsumer) {
		return new TypedHandle(
			BICONSUMER_ACCEPT
				.bindTo(biConsumer)
				.asType(methodType(void.class, argType1.rawClass(), argType2.rawClass())),
			VOID,
			List.of(argType1, argType2)
		);
	}

	public static <T> TypedHandle callable(KnownType argType, Callable<T> callable) {
		return new TypedHandle(
			CALLABLE_CALL
				.bindTo(callable)
				.asType(methodType(void.class, argType.rawClass())),
			VOID,
			List.of(argType)
		);
	}

	public static <T,R> TypedHandle function(KnownType argType, KnownType returnType, Function<T,R> function) {
		return new TypedHandle(
			FUNCTION_APPLY
				.bindTo(function)
				.asType(methodType(returnType.rawClass(), argType.rawClass())),
			returnType,
			List.of(argType)
		);
	}

	public static <T1,T2,R> TypedHandle biFunction(
		KnownType argType1,
		KnownType argType2,
		KnownType returnType,
		BiFunction<T1,T2,R> biFunction
	) {
		return new TypedHandle(
			BI_FUNCTION_APPLY
				.bindTo(biFunction)
				.asType(methodType(returnType.rawClass(), argType1.rawClass(), argType2.rawClass())),
			returnType,
			List.of(argType1, argType2)
		);
	}

	public static <T> TypedHandle predicate(KnownType argType, Predicate<T> predicate) {
		return new TypedHandle(
			PREDICATE_TEST
				.bindTo(predicate)
				.asType(methodType(boolean.class, argType.rawClass())),
			DataType.BOOLEAN,
			List.of(argType)
		);
	}

	private static final MethodHandle FUNCTION_APPLY;
	private static final MethodHandle BI_FUNCTION_APPLY;
	private static final MethodHandle PREDICATE_TEST;
	private static final MethodHandle SUPPLIER_GET;
	private static final MethodHandle CONSUMER_ACCEPT;
	private static final MethodHandle BICONSUMER_ACCEPT;
	private static final MethodHandle CALLABLE_CALL;

	static {
		try {
			FUNCTION_APPLY = MethodHandles.lookup().findVirtual(Function.class, "apply", methodType(Object.class, Object.class));
			BI_FUNCTION_APPLY = MethodHandles.lookup().findVirtual(BiFunction.class, "apply", methodType(Object.class, Object.class, Object.class));
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
