package works.bosk.junit;

import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.junit.ParameterInjectionSupport.Branch;

import static java.util.Arrays.asList;
import static works.bosk.junit.FieldInjectionContextProvider.NAMESPACE;
import static works.bosk.junit.ParameterInjectionSupport.cartesianProduct;
import static works.bosk.junit.ParameterInjectionSupport.computeBranches;

/**
 * Implements the {@link InjectFrom} annotation.
 */
public class ParameterInjectionContextProvider implements TestTemplateInvocationContextProvider {
	// TODO: I'm certain there are several accidentally quadratic algorithms in here.

	@Override
	public boolean supportsTestTemplate(ExtensionContext context) {
		return context.getTestMethod().isPresent();
	}

	@Override
	public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
		List<Parameter> requiredParameters = asList(context.getRequiredTestMethod().getParameters());

		List<Branch> neededBranches = computeBranches(context, requiredParameters, getClassLevelBranch(context));

		return neededBranches.stream().flatMap(branch -> {
			var valuesByInjector = new LinkedHashMap<Injector, List<?>>();
			requiredParameters.forEach(p -> {
				Injector injector = branch.injectorFor(p);
				if (injector == null) {
					// You might think this should be an error, but we do want to coexist
					// with other parameter resolvers, so we just back off and let them have a chance.
					// If there is no suitable resolver, JUnit will report that as an error.
					// TODO: If we let users annotate parameters with @Injected to indicate
					//  their intent, then we could throw an informative exception here.
				} else {
					valuesByInjector.computeIfAbsent(injector, key -> branch.toInject().get(key).values());
				}
			});

			List<Injector> injectors = List.copyOf(valuesByInjector.keySet());
			List<List<Object>> combinations = cartesianProduct(valuesByInjector.values());

			return combinations.stream().map(combo -> {
				// Swizzle the combo into a useful map from parameter to value
				var paramValueMap = new LinkedHashMap<Parameter, Object>();
				requiredParameters.forEach(parameter -> {
					// TODO: There's essentially a copy of this in Branch.withInjectors
					Injector pi = branch.injectorFor(parameter);
					if (pi != null) {
						int index = injectors.indexOf(pi);
						paramValueMap.put(parameter, combo.get(index));
					}
				});

				return new TestTemplateInvocationContext() {
					@Override
					public String getDisplayName(int invocationIndex) {
						return context.getRequiredTestMethod().getName() + "[" + invocationIndex + "] " + combo;
					}
					@Override
					public List<Extension> getAdditionalExtensions() {
						return List.of(new ParameterResolver() {
							@Override
							public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
								return paramValueMap.containsKey(pc.getParameter());
							}
							@Override
							public Object resolveParameter(ParameterContext pc, ExtensionContext ec) throws ParameterResolutionException {
								Parameter param = pc.getParameter();
								if (paramValueMap.containsKey(param)) {
									return paramValueMap.get(param);
								}
								throw new ParameterResolutionException("Parameter not bound: " + param);
							}
						});
					}
				};
			});
		});
	}

	private Branch getClassLevelBranch(ExtensionContext context) {
		// Store.get() automatically walks up the context hierarchy,
		// so the branch stored in ClassTemplateInvocationContext will be found
		var branch = context.getStore(NAMESPACE).get("branch", Branch.class);
		if (branch == null) {
			LOGGER.debug("No field injection detected; starting with empty branch");
			return Branch.empty();
		} else {
			LOGGER.debug("Continuing with branch from field injection: {}", branch);
			return branch;
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ParameterInjectionContextProvider.class);
}
