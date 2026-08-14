package works.bosk.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

public final class ReflectionHelpers {

	/**
	 * @param type must be defined in a classfile accessible by passing {@link Class#getResourceAsStream(String)}
	 *             the class's own name followed by <code>.class</code>.
	 *             In particular, this can't be a dynamically generated class.
	 * @param lookup must have access to all the methods of {@code type}.
	 * @return like {@link Class#getDeclaredMethods()} except in bytecode order.
	 */
	public static List<Method> getDeclaredMethodsInOrder(Class<?> type, MethodHandles.Lookup lookup) {
		classAccessProbe(type, lookup);

		List<Method> result = new ArrayList<>();
		ClassLoader loader = type.getClassLoader();
		ClassModel classModel;
		try {
			String typeName = type.getName();
			String fileName = typeName.substring(typeName.lastIndexOf('.') + 1) + ".class";
			InputStream resource = type.getResourceAsStream(fileName);
			if (resource == null) {
				throw new IOException("No resource called \"" + fileName + "\"");
			}
			try (resource) {
				classModel = ClassFile.of().parse(resource.readAllBytes());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to open the classfile corresponding to " + type, e);
		}
		for (MethodModel methodModel: classModel.methods()) {
			String name = methodModel.methodName().stringValue();
			if (name.equals("<init>") || name.equals("<clinit>")) {
				continue;
			} else if (methodModel.flags().has(AccessFlag.SYNTHETIC)) {
				continue;
			}
			MethodTypeDesc methodType = methodModel.methodTypeSymbol();
			List<ClassDesc> parameterTypes = methodType.parameterList();
			Class<?>[] argumentClasses = new Class<?>[parameterTypes.size()];
			for (int i = 0; i < parameterTypes.size(); i++) {
				argumentClasses[i] = findClass(parameterTypes.get(i), loader);
			}
			Class<?> returnClass = findClass(methodType.returnType(), loader);
			try {
				MethodHandle mh;
				if (methodModel.flags().has(AccessFlag.STATIC)) {
					mh = lookup.findStatic(type, name, MethodType.methodType(returnClass, argumentClasses));
				} else {
					mh = lookup.findVirtual(type, name, MethodType.methodType(returnClass, argumentClasses));
				}
				Method method = lookup.revealDirect(mh).reflectAs(Method.class, lookup);
				result.add(method);
			} catch (NoSuchMethodException e) {
				throw new IllegalStateException("Method found in bytecode cannot be retrieved via reflection", e);
			} catch (IllegalAccessException e) {
				throw new IllegalArgumentException("Unable to access method", e);
			}
		}
		return result;
	}

	private static void classAccessProbe(Class<?> type, MethodHandles.Lookup lookup) {
		// Look up a method that every class should have
		try {
			lookup.findVirtual(type, "toString", MethodType.methodType(String.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalArgumentException("Lookup object does not have access to the given type: " + type, e);
		}
	}

	private static Class<?> findClass(ClassDesc classDesc, ClassLoader loader) {
		try {
			if (classDesc.isPrimitive()) {
				return requireNonNull(Class.forPrimitiveName(classDesc.displayName()));
			} else if (classDesc.isArray()) {
				return findClass(classDesc.componentType(), loader).arrayType();
			} else {
				// Class.forName requires the dotted binary name, which we must
				// derive from the classfile's internal (slashy) descriptor.
				String descriptor = classDesc.descriptorString();
				String dottedName = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
				return requireNonNull(Class.forName(dottedName, false, loader));
			}
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Class<?> boxedClass(Class<?> valueClass) {
		return switch (valueClass.getName()) {
			case "int" -> Integer.class;
			case "long" -> Long.class;
			case "short" -> Short.class;
			case "char" -> Character.class;
			case "byte" -> Byte.class;
			case "float" -> Float.class;
			case "double" -> Double.class;
			case "boolean" -> Boolean.class;
			case "void" -> Void.class;
			default -> valueClass;
		};
	}

	public static Class<?> unboxedClass(Class<?> valueClass) {
		return switch (valueClass.getName()) {
			case "java.lang.Integer" -> int.class;
			case "java.lang.Long" -> long.class;
			case "java.lang.Short" -> short.class;
			case "java.lang.Character" -> char.class;
			case "java.lang.Byte" -> byte.class;
			case "java.lang.Float" -> float.class;
			case "java.lang.Double" -> double.class;
			case "java.lang.Boolean" -> boolean.class;
			case "java.lang.Void" -> void.class;
			default -> valueClass;
		};
	}
}
