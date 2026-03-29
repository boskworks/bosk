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
import works.bosk.junit.ParameterInjectionSupport.Branch;

import static java.util.stream.Collectors.joining;
import static works.bosk.junit.ParameterInjectionSupport.cartesianProduct;
import static works.bosk.junit.ParameterInjectionSupport.expandBranchesForClassLevel;
import static works.bosk.junit.ParameterInjectionSupport.getAllInjectorClasses;
import static works.bosk.junit.ParameterInjectionSupport.getInjectedFields;

/**
 * Implements class-level field injection via {@link InjectFrom}.
 * <p>
 * This extension reads {@code @InjectFrom} annotations from the test class hierarchy
 * and creates multiple invocations of the test class, one per branch (combination of
 * injector values).
 *
 * @see InjectFrom
 * @see Injected
 */
public class FieldInjectionContextProvider implements ClassTemplateInvocationContextProvider {
	static final ExtensionContext.Namespace NAMESPACE =
		ExtensionContext.Namespace.create(FieldInjectionContextProvider.class);

	@Override
	public boolean supportsClassTemplate(ExtensionContext context) {
		// If there are no injected fields, there's nothing for this extension to do,
		// and ParameterInjectionContextProvider can operate on its own without us.
		return !getInjectedFields(context).isEmpty();
	}

	@Override
	public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(ExtensionContext context) {
		List<Field> injectedFields = getInjectedFields(context);
		List<Branch> branches = computeBranchesForClass(context);

		return branches.stream().flatMap(branch -> {
			var valuesByInjector = new LinkedHashMap<Injector, List<?>>();
			for (Field f : injectedFields) {
				Injector injector = branch.injectorForField(f);
				if (injector == null) {
					// There is no other mechanism for supplying values for fields annotated
					// with @Injected, so we're doomed.
					throw new ParameterResolutionException("No injector for field " + f);
				} else {
					valuesByInjector.computeIfAbsent(injector, key -> branch.toInject().get(key).values());
				}
			}

			List<Injector> injectors = List.copyOf(valuesByInjector.keySet());
			List<List<Object>> combinations = cartesianProduct(valuesByInjector.values());

			return combinations.stream().map(combo -> {
				var fieldValueMap = new LinkedHashMap<Field, Object>();
				for (Field field : injectedFields) {
					Injector pi = branch.injectorForField(field);
					if (pi != null) {
						int injectorIndex = injectors.indexOf(pi);
						fieldValueMap.put(field, combo.get(injectorIndex));
					}
				}

				Branch collapsedBranch = branch.withFieldValues(fieldValueMap);
				LOGGER.debug("Storing collapsed branch in context during preparation: {}", collapsedBranch);
				context.getStore(NAMESPACE).put("branch", collapsedBranch);

				return new ClassTemplateInvocationContext() {
					@Override
					public String getDisplayName(int invocationIndex) {
						return displayName(branch, fieldValueMap, injectedFields);
					}

					@Override
					public List<Extension> getAdditionalExtensions() {
						return List.of(new TestInstancePostProcessor() {
							@Override
							public void postProcessTestInstance(Object testInstance, ExtensionContext ec) {
								setInjectedFields(testInstance, fieldValueMap, injectedFields);
							}
						});
					}
				};
			});
		});
	}

	private String displayName(Branch branch, Map<Field, Object> fieldValueMap, List<Field> injectedFields) {
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
				field.setAccessible(true);
				field.set(testInstance, value);
			} catch (IllegalAccessException e) {
				throw new ParameterResolutionException("Cannot set field " + field, e);
			}
		}
	}

	private List<Branch> computeBranchesForClass(ExtensionContext context) {
		var injectedFields = getInjectedFields(context);

		List<Branch> branches = List.of(Branch.empty());
		for (var injectorClass : getAllInjectorClasses(context)) {
			if (ParameterInjectionSupport.supportsAnyField(injectorClass, injectedFields)) {
				branches = expandBranchesForClassLevel(branches, injectorClass);
			}
		}
		return branches;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FieldInjectionContextProvider.class);
}
