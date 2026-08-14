package works.bosk.bytecode;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

import static java.lang.classfile.TypeKind.REFERENCE;
import static java.lang.reflect.Modifier.isStatic;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.util.ReflectionHelpers.boxedClass;

/**
 * Stateless helpers for emitting common instruction sequences into a {@link CodeBuilder}.
 */
public final class Codegen {

	/**
	 * Emit a CHECKCAST: <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.checkcast">...</a>
	 */
	public static void castTo(CodeBuilder cb, Class<?> expectedType) {
		if (!expectedType.isPrimitive()) {
			cb.checkcast(GeneratedClass.cd(expectedType));
		}
	}

	/**
	 * Emit the appropriate INVOKE instruction for the given Method.
	 */
	public static void invoke(CodeBuilder cb, Method method) {
		Class<?> type = method.getDeclaringClass();
		ClassDesc owner = GeneratedClass.cd(type);
		String methodName = method.getName();
		MethodTypeDesc methodType = GeneratedClass.mtd(method.getReturnType(), method.getParameterTypes());
		if (isStatic(method.getModifiers())) {
			cb.invokestatic(owner, methodName, methodType);
		} else if (type.isInterface()) {
			cb.invokeinterface(owner, methodName, methodType);
		} else {
			cb.invokevirtual(owner, methodName, methodType);
		}
	}

	/**
	 * Emit INVOKESPECIAL for the given Constructor.
	 */
	public static void invoke(CodeBuilder cb, Constructor<?> ctor) {
		cb.invokespecial(GeneratedClass.cd(ctor.getDeclaringClass()), "<init>", GeneratedClass.mtd(void.class, ctor.getParameterTypes()));
	}

	/**
	 * Emits a call to the given method handle, which is stored as a curried
	 * value so the generated code can load it directly.
	 * <p>
	 * The arguments are already on the operand stack (the generators push them
	 * before the step runs), but {@code invokeExact} expects the handle itself
	 * to be the receiver, at the bottom of the stack. {@link #pushHandle} moves
	 * the handle into position, setting the arguments aside in local variables
	 * so it can be pushed first, then restored. (The JIT optimizes the
	 * store-and-reload away.)
	 */
	public static void invokeExact(CodeBuilder cb, Currier currier, MethodHandle handle, String name) {
		pushHandle(cb, currier, handle, name);
		cb.invokevirtual(cb.constantPool().methodRefEntry(
			GeneratedClass.cd(MethodHandle.class),
			"invokeExact",
			handle.type().describeConstable().get()
		));
	}

	/**
	 * Pushes the given method handle onto the operand stack and rotates it into
	 * position under the arguments that are already there, based on the handle's
	 * signature, so that it ends up as the receiver of the subsequent invocation.
	 */
	private static void pushHandle(CodeBuilder cb, Currier currier, MethodHandle handle, String name) {
		int numParams = handle.type().parameterCount();
		LocalVariable[] args = new LocalVariable[numParams];
		for (int i = numParams - 1; i >= 0; i--) {
			TypeKind typeKind = TypeKind.fromDescriptor(handle.type().parameterType(i).descriptorString());
			args[i] = popToLocal(cb, typeKind);
		}
		currier.pushCurried(cb, name, handle, MethodHandle.class);
		for (LocalVariable arg : args) {
			cb.loadLocal(arg.type(), arg.slot());
		}
	}

	public static void autoBox(CodeBuilder cb, java.lang.reflect.Type valueType) {
		Class<?> valueClass = rawClass(valueType);
		if (valueClass.isPrimitive()) {
			try {
				invoke(cb, boxedClass(valueClass).getMethod("valueOf", valueClass));
			} catch (NoSuchMethodException e) {
				throw new AssertionError("Expected boxing method to exist", e);
			}
		}
	}

	public static void autoUnbox(CodeBuilder cb, java.lang.reflect.Type valueType) {
		Class<?> valueClass = rawClass(valueType);
		if (valueClass.isPrimitive()) {
			try {
				invoke(cb, boxedClass(valueClass).getMethod(valueClass.getName() + "Value"));
			} catch (NoSuchMethodException e) {
				throw new AssertionError("Expected unboxing method to exist", e);
			}
		}
	}

	/**
	 * Emit ASTORE: <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.astore">...</a>
	 */
	public static LocalVariable popToLocal(CodeBuilder cb) {
		return popToLocal(cb, REFERENCE);
	}

	/**
	 * Emit the appropriate store opcode for the given type.
	 */
	public static LocalVariable popToLocal(CodeBuilder cb, TypeKind typeKind) {
		int slot = cb.allocateLocal(typeKind);
		cb.storeLocal(typeKind, slot);
		return new LocalVariable(typeKind, slot);
	}

	/**
	 * @param index the JVM local-variable slot for the desired parameter.
	 * For instance methods, slot 0 is the receiver ({@code this}), so the
	 * first parameter is at slot 1. This method assumes all parameters are
	 * single-slot types; it does not account for {@code long} or
	 * {@code double} parameters, which occupy two slots.
	 *
	 * @return a {@link LocalVariable} representing the reference-typed
	 * parameter at the given slot.
	 */
	public static LocalVariable parameter(CodeBuilder cb, int index) {
		return new LocalVariable(REFERENCE, cb.parameterSlot(index - 1));
	}

	/**
	 * Adds line number info for the caller of the generated instruction, so that
	 * stack traces and disassemblies point at the source code that generated them.
	 */
	public static void lineInfo(CodeBuilder cb, StackWalker.StackFrame sourceFileOrigin) {
		String sourceFileName = sourceFileOrigin.getFileName();
		StackWalker.StackFrame bestFrame = StackWalker.getInstance().walk(frames -> frames
			.filter(frame -> Objects.equals(sourceFileName, frame.getFileName()))
			.findFirst()
			.orElse(sourceFileOrigin)
		);
		cb.lineNumber(bestFrame.getLineNumber());
	}

	private Codegen() {}
}
