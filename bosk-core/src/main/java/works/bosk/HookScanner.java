package works.bosk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.annotations.Hook;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.ReflectionHelpers;

import static java.lang.reflect.Modifier.isPrivate;
import static java.lang.reflect.Modifier.isStatic;

/**
 * Finds methods annotated with {@link Hook} in the given {@code object} and registers them in the given {@link Bosk}.
 * If a subclass overrides a hook method, only the most-derived override is registered.
 */
final class HookScanner {
	static <T> void registerHooks(T receiverObject, RootReference<?> rootReference, HookRegistrar hookRegistrar, MethodHandles.Lookup lookup) throws InvalidTypeException {
		List<Class<?>> bottomUpHierarchy = new ArrayList<>();
		for (Class<?> receiverClass = receiverObject.getClass(); receiverClass != Object.class; receiverClass = receiverClass.getSuperclass()) {
			bottomUpHierarchy.add(receiverClass);
		}
		// Collect hook methods from all classes in the hierarchy. If a subclass overrides a hook method,
		// the override replaces the original, so only the most-derived version of each signature is registered.
		Map<MethodSignature, HookMethod> hookMethodsBySignature = new LinkedHashMap<>();
		for (Class<?> receiverClass: bottomUpHierarchy.reversed()) {
			List<Method> methods;
			try {
				methods = ReflectionHelpers.getDeclaredMethodsInOrder(receiverClass, lookup);
			} catch (IllegalArgumentException e) {
				throw new InvalidTypeException("Supplied Lookup object does not have the necessary access", e);
			}
			for (Method method : methods) {
				Hook hookAnnotation = method.getAnnotation(Hook.class);
				if (hookAnnotation == null) {
					continue;
				}
				if (isStatic(method.getModifiers())) {
					throw new InvalidTypeException("Hook method cannot be static: " + method);
				} else if (isPrivate(method.getModifiers())) {
					throw new InvalidTypeException("Hook method cannot be private: " + method);
				}
				hookMethodsBySignature.put(MethodSignature.of(method), new HookMethod(receiverClass, method, hookAnnotation.value()));
			}
		}
		int hookCounter = 0;
		for (HookMethod hookMethod : hookMethodsBySignature.values()) {
			try {
				registerOneHookMethod(receiverObject, hookMethod.method, Path.parseParameterized(hookMethod.scope), rootReference, hookRegistrar, lookup);
				hookCounter++;
			} catch (InvalidTypeException e) {
				throw new InvalidTypeException("Unable to register hook method " + hookMethod.receiverClass.getSimpleName() + "." + hookMethod.method.getName() + ": " + e.getMessage(), e);
			}
		}
		if (hookCounter == 0) {
			LOGGER.warn("Found no hook methods in {}; may be misconfigured", receiverObject.getClass().getSimpleName());
		} else {
			LOGGER.info("Registered {} hook{} in {}", hookCounter, (hookCounter >= 2) ? "s" : "", receiverObject.getClass().getSimpleName());
		}
	}

	private static <T> void registerOneHookMethod(T receiverObject, Method method, Path path, RootReference<?> rootReference, HookRegistrar hookRegistrar, MethodHandles.Lookup lookup) throws InvalidTypeException {
		Reference<?> plainRef = rootReference.then(Object.class, path);

		// Now substitute one of the handy Reference subtypes where possible
		Reference<?> scope;
		if (Catalog.class.isAssignableFrom(plainRef.targetClass())) {
			scope = rootReference.thenCatalog(Entity.class, path);
		} else if (Listing.class.isAssignableFrom(plainRef.targetClass())) {
			scope = rootReference.thenListing(Entity.class, path);
		} else if (SideTable.class.isAssignableFrom(plainRef.targetClass())) {
			scope = rootReference.thenSideTable(Entity.class, Object.class, path);
		} else {
			scope = plainRef;
		}

		List<Function<Reference<?>, Object>> argumentFunctions = new ArrayList<>(method.getParameterCount());
		argumentFunctions.add(_ -> receiverObject); // The "this" pointer
		for (Parameter p : method.getParameters()) {
			if (Reference.class.isAssignableFrom(p.getType())) {
				Type referenceType = ReferenceUtils.parameterType(p.getParameterizedType(), Reference.class, 0);
				if (referenceType.equals(scope.targetType())) {
					argumentFunctions.add(ref -> ref);
				} else {
					throw new InvalidTypeException("Parameter \"" + p.getName() + "\" should be a reference to " + scope.targetType() + ", not " + referenceType);
				}
			} else if (BindingEnvironment.class.isAssignableFrom(p.getType())) {
				// The above check might look backward because you can assign a BindingEnvironment
				// to a parameter if that parameter is assignable from BindingEnvironment.
				// However, we expect some level of intention expressed by the user:
				// we don't want to pass a BindingEnvironment to a parameter of type Object.
				// BindingEnvironment has no supertypes except Object, and it's final,
				// so we're effectively checking that the parameter is of type BindingEnvironment exactly.
				argumentFunctions.add(ref -> scope.parametersFrom(ref.path()));
			} else {
				throw new InvalidTypeException("Unsupported parameter type: " + p);
			}
		}

		MethodHandle hook;
		try {
			hook = lookup.unreflect(method);
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException(e);
		}
		hookRegistrar.registerHook(method.getName(), scope, ref -> {
			try {
				List<Object> arguments = new ArrayList<>(argumentFunctions.size());
				argumentFunctions.forEach(f -> arguments.add(f.apply(ref)));
				hook.invokeWithArguments(arguments);
			} catch (Throwable e) {
				throw new IllegalStateException("Unable to call hook \"" + method.getName() + "\"", e);
			}
		});
	}

	private HookScanner() {}

	private record HookMethod(Class<?> receiverClass, Method method, String scope) { }

	private record MethodSignature(String name, List<Class<?>> parameterTypes) {
		static MethodSignature of(Method method) {
			return new MethodSignature(method.getName(), List.of(method.getParameterTypes()));
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(HookScanner.class);
}
