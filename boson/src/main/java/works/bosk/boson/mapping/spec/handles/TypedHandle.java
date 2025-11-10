package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import works.bosk.boson.types.DataType;

import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

public record TypedHandle(
	MethodHandle handle,
	DataType returnType,
	List<? extends DataType> parameterTypes
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

	private static MethodType equivalentMethodType(DataType returnType, List<? extends DataType> parameterTypes) {
		return methodType(
			equivalentClass(returnType),
			parameterTypes.stream().map(TypedHandle::equivalentClass).toArray(Class<?>[]::new)
		);
	}

	private static Class<?> equivalentClass(DataType dataType) {
		return dataType.leastUpperBoundClass();
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

	public TypedHandle bind(int parameterIndex, TypedHandle parameter) {
		// Drop the formality: cast the parameter handle to the expected type
		MethodHandle parameterHandle = parameter.handle()
			.asType(parameter.handle.type()
				.changeReturnType(handle.type().parameterType(parameterIndex)));
		var resultHandle = MethodHandles.collectArguments(handle, parameterIndex, parameterHandle);
		List<DataType> resultParameterTypes = new ArrayList<>(parameterTypes());
		resultParameterTypes.remove(parameterIndex);
		resultParameterTypes.addAll(parameter.parameterTypes());
		return new TypedHandle(resultHandle, returnType, resultParameterTypes);
	}

	public TypedHandle dropArguments(int pos, DataType... argTypes) {
		MethodHandle resultHandle = MethodHandles.dropArguments(
			handle,
			pos,
			Stream.of(argTypes)
				.map(TypedHandle::equivalentClass)
				.toArray(Class<?>[]::new)
		);
		List<DataType> resultParameterTypes = new ArrayList<>(parameterTypes());
		resultParameterTypes.addAll(pos, List.of(argTypes));
		return new TypedHandle(resultHandle, returnType, List.copyOf(resultParameterTypes));
	}

	public TypedHandle substitute(Map<String, DataType> actualArguments) {
		DataType returnType = this.returnType.substitute(actualArguments);
		List<DataType> parameterTypes = this.parameterTypes.stream()
			.map(t -> t.substitute(actualArguments))
			.toList();
		MethodHandle handle = this.handle.asType(
			equivalentMethodType(returnType, parameterTypes));
		return new TypedHandle(handle, returnType, parameterTypes);
	}

	@Override
	public String toString() {
		return "(" + String.join(", ", parameterTypes.stream().map(DataType::toString).toList()) + ")->" + returnType;
	}

}
