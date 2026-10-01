package works.bosk.junit;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.junit.InjectionSupport.Branch;
import works.bosk.junit.InjectionSupport.InjectionKey;

import static java.util.stream.Collectors.joining;
import static works.bosk.junit.InjectionSupport.BRANCH_KEY;
import static works.bosk.junit.InjectionSupport.NAMESPACE;
import static works.bosk.junit.InjectionSupport.cartesianProduct;
import static works.bosk.junit.InjectionSupport.computeBranchesForFields;
import static works.bosk.junit.InjectionSupport.getInjectedFields;
import static works.bosk.junit.InjectionSupport.setAccessible;

/**
 * Implements class-level field injection via {@link InjectFrom}.
 * <p>
 * This extension reads {@code @InjectFrom} annotations from the test class hierarchy
 * and creates multiple invocations of the test class, one per combination of injected values.
 *
 * @see InjectFrom
 * @see Injected
 */
public class FieldInjectionContextProvider implements ClassTemplateInvocationContextProvider {
	@Override
	public boolean supportsClassTemplate(ExtensionContext context) {
		// If there are no injected fields, there's nothing for this extension to do,
		// and ParameterInjectionContextProvider can operate on its own without us.
		return !getInjectedFields(context).isEmpty();
	}

	@Override
	public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(ExtensionContext context) {
		List<Field> injectedFields = getInjectedFields(context);
		// The class this invocation was created for. When a nested test runs, the
		// class template that injects its fields is the enclosing class, and these
		// fields belong to that enclosing class's own instance.
		Class<?> testClass = context.getRequiredTestClass();

		List<Branch> branches = computeBranchesForFields(context);
		return branches.stream().flatMap(branch -> {
			var valuesByKey = new LinkedHashMap<InjectionKey, List<?>>();
			for (Field f : injectedFields) {
				var key = branch.keyForField(f);
				if (key == null) {
					// There is no other mechanism for supplying values for fields annotated
					// with @Injected, so we're doomed.
					throw new ParameterResolutionException("No injector for field " + f);
				} else {
					valuesByKey.computeIfAbsent(key, branch::valuesFor);
				}
			}

			// These lists have matching indexes
			List<InjectionKey> keys = List.copyOf(valuesByKey.keySet());
			List<List<Object>> combinations = cartesianProduct(valuesByKey.values());

			return combinations.stream().map(combo -> {
				var fieldValueMap = new LinkedHashMap<Field, Object>();
				for (Field field : injectedFields) {
					InjectionKey key = branch.keyForField(field);
					if (key != null) {
						// We've found a field to inject
						fieldValueMap.put(field, combo.get(keys.indexOf(key)));
					}
				}

				Branch collapsedBranch = branch.withFieldValues(fieldValueMap);

				return new ClassTemplateInvocationContext() {
					@Override
					public String getDisplayName(int invocationIndex) {
						return displayName(fieldValueMap, injectedFields);
					}

					@Override
					public List<Extension> getAdditionalExtensions() {
						return List.of((TestInstancePostProcessor) (testInstance, _) -> {
							// JUnit also invokes this post-processor, as an inherited
							// extension, on any nested test classes. Those instances
							// belong to a different class template, and writing the
							// enclosing class's fields to them would either throw or
							// clobber the nested instance's own injected fields.
							if (testInstance.getClass() == testClass) {
								setInjectedFields(testInstance, fieldValueMap, injectedFields);
							} else {
								LOGGER.debug("Skipping field injection for {} into {}",
									testClass.getSimpleName(), testInstance.getClass().getSimpleName());
							}
						});
					}

					@Override
					public void prepareInvocation(ExtensionContext context) {
						LOGGER.debug("Storing collapsed branch in invocation context: {}", collapsedBranch);
						context.getStore(NAMESPACE).put(BRANCH_KEY, collapsedBranch);
					}
				};
			});
		});
	}

	private String displayName(Map<Field, Object> fieldValueMap, List<Field> injectedFields) {
		return injectedFields.stream()
			.map(f -> f.getName() + "=" + fieldValueMap.get(f))
			.collect(joining(", "));
	}

	/**
	 * Set the field values on the test instance.
	 */
	private void setInjectedFields(Object testInstance, Map<Field, Object> fieldValueMap, List<Field> injectedFields) {
		for (Field field : injectedFields) {
			Object value = fieldValueMap.get(field);
			try {
				setAccessible(field);
				field.set(testInstance, value);
			} catch (IllegalAccessException e) {
				throw new ParameterResolutionException("Cannot set field " + field, e);
			}
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FieldInjectionContextProvider.class);
}
