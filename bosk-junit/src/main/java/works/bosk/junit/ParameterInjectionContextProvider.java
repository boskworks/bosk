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
import works.bosk.junit.InjectionSupport.Branch;
import works.bosk.junit.InjectionSupport.InjectionKey;

import static java.util.Arrays.asList;
import static works.bosk.junit.InjectionSupport.cartesianProduct;
import static works.bosk.junit.InjectionSupport.computeBranchesForParameters;

/**
 * Implements method-level parameter injection via  {@link InjectFrom}.
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

		List<Branch> branches = computeBranchesForParameters(context, requiredParameters, getClassLevelBranch(context));
		return branches.stream().flatMap(branch -> {
			var valuesByKey = new LinkedHashMap<InjectionKey, List<?>>();
			requiredParameters.forEach(p -> {
				InjectionKey key = branch.keyForParameter(p);
				if (key == null) {
					// You might think this should be an error, but we do want to coexist
					// with other parameter resolvers, so we just back off and let them have a chance.
					// If there is no suitable resolver, JUnit will report that as an error.
					// TODO: If we let users annotate parameters with @Injected to indicate
					//  their intent, then we could throw an informative exception here.
				} else {
					valuesByKey.computeIfAbsent(key, k -> branch.valuesFor(k));
				}
			});

			// These lists have matching indexes
			List<InjectionKey> keys = List.copyOf(valuesByKey.keySet());
			List<List<Object>> combinations = cartesianProduct(valuesByKey.values());

			return combinations.stream().map(combo -> {
				// Swizzle the combo into a useful map from parameter to value
				var paramValueMap = new LinkedHashMap<Parameter, Object>();
				requiredParameters.forEach(parameter -> {
					// TODO: There's essentially a copy of this in Branch.expandedFor
					InjectionKey key = branch.keyForParameter(parameter);
					if (key != null) {
						int index = keys.indexOf(key);
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
		Branch branch = InjectionSupport.enclosingBranch(context);
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
