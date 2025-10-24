package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.List;
import works.bosk.boson.types.KnownType;

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

}
