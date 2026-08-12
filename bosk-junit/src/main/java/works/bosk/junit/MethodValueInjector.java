package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import static java.util.stream.Collectors.toList;

/**
 * An {@link Injector} backed by an {@link InjectorMethod @InjectorMethod}-annotated
 * method. One instance is created per combination of the method's parameter
 * values, with those values bound as the invocation arguments.
 */
final class MethodValueInjector implements Injector {
	private final Method method;
	private final Class<?> elementType;
	private final Object[] args;
	private List<?> values;

	MethodValueInjector(Method method, Class<?> elementType, Object[] args) {
		this.method = method;
		this.elementType = elementType;
		this.args = args;
	}

	Method method() {
		return method;
	}

	@Override
	public Class<?> injectorClass() {
		return method.getDeclaringClass();
	}

	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType == this.elementType;
	}

	@Override
	public List<?> values() {
		if (values == null) {
			try {
				Object result = method.invoke(null, args);
				if (result instanceof Stream<?> stream) {
					values = stream.collect(toList());
				} else {
					throw new ParameterResolutionException(
						"@InjectorMethod " + method + " must return a Stream, but returned " + result);
				}
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RuntimeException runtimeException) {
					throw runtimeException;
				}
				throw new ParameterResolutionException("Error invoking @InjectorMethod " + method, cause);
			} catch (ReflectiveOperationException e) {
				throw new ParameterResolutionException("Error invoking @InjectorMethod " + method, e);
			}
		}
		return values;
	}

	@Override
	public String toString() {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "(...)";
	}
}
