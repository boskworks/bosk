package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.List;

/**
 * An {@link Injector} that injects each value of an enum, one per invocation.
 */
final class EnumValueInjector implements Injector {
	private final Class<?> enumClass;
	private final List<?> enumValues;

	EnumValueInjector(Class<?> enumClass, List<?> enumValues) {
		this.enumClass = enumClass;
		this.enumValues = enumValues;
	}

	@Override
	public Class<?> injectorClass() {
		// An enum injector is represented by the enum class
		return enumClass;
	}

	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType == enumClass;
	}

	@Override
	public List<?> values() {
		return enumValues;
	}

	@Override
	public String toString() {
		return enumClass.getSimpleName();
	}
}
