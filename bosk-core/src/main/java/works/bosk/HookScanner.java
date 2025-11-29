package works.bosk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.annotations.Hook;
import works.bosk.exceptions.InvalidTypeException;

import static java.lang.reflect.Modifier.isPrivate;
import static java.lang.reflect.Modifier.isStatic;
import static works.bosk.util.ReflectionHelpers.getDeclaredMethodsInOrder;

/**
 * Finds methods annotated with {@link Hook} in the given {@code object} and registers them in the given {@link Bosk}.
 */
final class HookScanner {
	static <T> void registerHooks(T receiverObject, Bosk<?> bosk) throws InvalidTypeException {
		int hookCounter = 0;
		for (
			Class<?> receiverClass = receiverObject.getClass();
			receiverClass != null;
			receiverClass = receiverClass.getSuperclass()
		) {
			for (Method method : getDeclaredMethodsInOrder(receiverClass)) {
				Hook hookAnnotation = method.getAnnotation(Hook.class);
				if (hookAnnotation == null) {
					continue;
				}
				if (isStatic(method.getModifiers())) {
					throw new InvalidTypeException("Hook method cannot be static: " + method);
				} else if (isPrivate(method.getModifiers())) {
					throw new InvalidTypeException("Hook method cannot be private: " + method);
				}

				try {
					registerOneHookMethod(receiverObject, bosk, method, Path.parseParameterized(hookAnnotation.value()));
					hookCounter++;
				} catch (InvalidTypeException e) {
					throw new InvalidTypeException("Unable to register hook method " + receiverClass.getSimpleName() + "." + method.getName() + ": " + e.getMessage(), e);
				}
			}
		}
		if (hookCounter == 0) {
			LOGGER.warn("Found no hook methods in {}; may be misconfigured", receiverObject.getClass().getSimpleName());
		} else {
			LOGGER.info("Registered {} hook{} in {}", hookCounter, (hookCounter >= 2) ? "s" : "", receiverObject.getClass().getSimpleName());
		}
	}

	private static <T> void registerOneHookMethod(T receiverObject, Bosk<?> bosk, Method method, Path path) throws InvalidTypeException {
		Reference<?> plainRef = bosk.rootReference().then(Object.class, path);

		// Now substitute one of the handy Reference subtypes where possible
		Reference<?> scope;
		if (Catalog.class.isAssignableFrom(plainRef.targetClass())) {
			scope = bosk.rootReference().thenCatalog(Entity.class, path);
		} else if (Listing.class.isAssignableFrom(plainRef.targetClass())) {
			scope = bosk.rootReference().thenListing(Entity.class, path);
		} else if (SideTable.class.isAssignableFrom(plainRef.targetClass())) {
			scope = bosk.rootReference().thenSideTable(Entity.class, Object.class, path);
		} else {
			scope = plainRef;
		}

		List<Function<Reference<?>, Object>> argumentFunctions = new ArrayList<>(method.getParameterCount());
		argumentFunctions.add(ref -> receiverObject); // The "this" pointer
		for (Parameter p : method.getParameters()) {
			if (Reference.class.isAssignableFrom(p.getType())) {
				Type referenceType = ReferenceUtils.parameterType(p.getParameterizedType(), Reference.class, 0);
				if (referenceType.equals(scope.targetType())) {
					argumentFunctions.add(ref -> ref);
				} else {
					throw new InvalidTypeException("Parameter \"" + p.getName() + "\" should be a reference to " + scope.targetType() + ", not " + referenceType);
				}
			} else if (p.getType().isAssignableFrom(BindingEnvironment.class)) {
				argumentFunctions.add(ref -> scope.parametersFrom(ref.path()));
			} else {
				throw new InvalidTypeException("Unsupported parameter type: " + p);
			}
		}

		MethodHandle hook;
		try {
			hook = MethodHandles.lookup().unreflect(method);
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException(e);
		}
		bosk.registerHook(method.getName(), scope, ref -> {
			try {
				List<Object> arguments = new ArrayList<>(argumentFunctions.size());
				argumentFunctions.forEach(f -> arguments.add(f.apply(ref)));
				hook.invokeWithArguments(arguments);
			} catch (Throwable e) {
				throw new IllegalStateException("Unable to call hook \"" + method.getName() + "\"", e);
			}
		});
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(HookScanner.class);
}
