package works.bosk.junit;

import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.List;

/**
 * Provides a series of possible values for a given parameter.
 * <p>
 * Note that these will be added to a {@link HashSet}.
 * Since only one injector of each class is in use at a time,
 * the default {@link Object#equals equals} and {@link Object#hashCode hashCode}
 * behaviour will work fine,
 * but if you override them, strange behaviour may result;
 * for example, parts of the injection logic may be unable to distinguish
 * two different injectors.
 */
public interface ParameterInjector {
	/**
	 * Note: if this method returns different results for the same parameter
	 * at different times, strange behaviour may result.
	 * @return true if this injector provides values for the given parameter.
	 */
	boolean supportsParameter(Parameter parameter);

	/**
	 * @return non-null {@link List} of values for the given parameter.
	 */
	List<Object> values();

	@SuppressWarnings("unchecked")
	static <T> ParameterInjector ofType(Class<T> type, List<? extends T> values) {
		return new ParameterInjector() {
			@Override
			public boolean supportsParameter(Parameter p) {
				return p.getType() == type;
			}
			@Override
			public List<Object> values() {
				return (List<Object>) values;
			}
		};
	}
}
