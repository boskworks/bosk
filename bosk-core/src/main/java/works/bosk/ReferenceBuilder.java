package works.bosk;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import works.bosk.annotations.ReferencePath;
import works.bosk.bytecode.Currier;
import works.bosk.bytecode.GeneratedClass;
import works.bosk.exceptions.InvalidTypeException;

import static java.lang.classfile.TypeKind.REFERENCE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static works.bosk.ReferenceUtils.parameterType;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.bytecode.Codegen.invoke;
import static works.bosk.bytecode.Codegen.lineInfo;
import static works.bosk.bytecode.GeneratedClass.here;

class ReferenceBuilder {
	@SuppressWarnings({"unchecked","rawtypes"})
	static <T> T buildReferences(Class<T> refsClass, Bosk<?> bosk) throws InvalidTypeException {
		List<MethodBinding> bindings = new ArrayList<>();
		for (Method method: refsClass.getDeclaredMethods()) { // TODO: Inherited methods
			ReferencePath referencePath = method.getAnnotation(ReferencePath.class);
			if (referencePath == null) {
				throw new InvalidTypeException("Missing " + ReferencePath.class.getSimpleName() + " annotation on " + methodName(method));
			}
			Type returnType = method.getGenericReturnType();
			Class<?> returnClass = rawClass(returnType);
			if (!Reference.class.isAssignableFrom(returnClass)) {
				throw new InvalidTypeException("Expected " + methodName(method) + " to return a Reference");
			}
			Type targetType = parameterType(returnType, Reference.class, 0);
			Reference<?> result;
			try {
				Path path = Path.parseParameterized(referencePath.value());
				if (returnClass.equals(CatalogReference.class)) {
					Type entryType = parameterType(returnType, CatalogReference.class, 0);
					result = bosk.rootReference().thenCatalog((Class) rawClass(entryType), path);
				} else if (returnClass.equals(ListingReference.class)) {
					Type entryType = parameterType(returnType, ListingReference.class, 0);
					result = bosk.rootReference().thenListing((Class) rawClass(entryType), path);
				} else if (returnClass.equals(SideTableReference.class)) {
					Type keyType = parameterType(returnType, SideTableReference.class, 0);
					Type valueType = parameterType(returnType, SideTableReference.class, 1);
					result = bosk.rootReference().thenSideTable((Class) rawClass(keyType), (Class) rawClass(valueType), path);
				} else {
					result = bosk.rootReference().then(rawClass(targetType), path);
				}
			} catch (InvalidTypeException e) {
				// Add some troubleshooting info for the user
				throw new InvalidTypeException("Reference type mismatch on " + methodName(method) + ": " + e.getMessage(), e);
			}
			for (Parameter p: method.getParameters()) {
				if (!Identifier.class.isAssignableFrom(p.getType())
					&& !Identifier[].class.isAssignableFrom(p.getType())
					&& !BindingEnvironment.class.isAssignableFrom(p.getType())) {
					throw new InvalidTypeException("Unexpected parameter type " + p.getType().getSimpleName() + " on " + methodName(method));
				}
			}
			bindings.add(new MethodBinding(method, result));
		}
		StackWalker.StackFrame origin = here();
		Currier currier = new Currier();
		return GeneratedClass.instantiate(
			"REFS_" + refsClass.getSimpleName(),
			refsClass,
			refsClass.getClassLoader(),
			origin,
			currier,
			cb -> {
				for (MethodBinding binding: bindings) {
					Method method = binding.method();
					cb.withMethodBody(method.getName(), GeneratedClass.mtd(method.getReturnType(), method.getParameterTypes()), PUBLIC.mask(), codeBuilder -> {
						lineInfo(codeBuilder, origin);
						currier.pushCurried(codeBuilder, method.getName(), binding.result(), Reference.class);
						int parameterIndex = 0;
						for (Parameter p: method.getParameters()) {
							codeBuilder.loadLocal(REFERENCE, codeBuilder.parameterSlot(parameterIndex++));
							if (Identifier.class.isAssignableFrom(p.getType())) {
								invoke(codeBuilder, REFERENCE_BOUND_TO_ID);
							} else if (Identifier[].class.isAssignableFrom(p.getType())) {
								invoke(codeBuilder, REFERENCE_BOUND_TO_ARRAY);
							} else if (BindingEnvironment.class.isAssignableFrom(p.getType())) {
								invoke(codeBuilder, REFERENCE_BOUND_BY);
							} else {
								// Should have been rejected in the validation loop above
								throw new AssertionError("Unexpected parameter type " + p.getType().getSimpleName() + " on " + methodName(method));
							}
						}
						codeBuilder.areturn();
					});
				}
			});
	}

	private record MethodBinding(Method method, Reference<?> result) { }

	@NonNull
	private static String methodName(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}

	static final Method REFERENCE_BOUND_TO_ARRAY;
	static final Method REFERENCE_BOUND_TO_ID;
	static final Method REFERENCE_BOUND_BY;

	static {
		try {
			REFERENCE_BOUND_TO_ARRAY = Reference.class.getDeclaredMethod("boundTo", Identifier[].class);
			REFERENCE_BOUND_TO_ID = Runtime.class.getDeclaredMethod("boundTo", Reference.class, Identifier.class);
			REFERENCE_BOUND_BY = Reference.class.getDeclaredMethod("boundBy", BindingEnvironment.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	public static final class Runtime {
		public static Reference<?> boundTo(Reference<?> ref, Identifier id) {
			return ref.boundTo(id);
		}
	}

}
