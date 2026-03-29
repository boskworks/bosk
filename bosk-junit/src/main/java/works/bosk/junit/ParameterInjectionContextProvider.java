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
import works.bosk.junit.ParameterInjectionSupport.Branch;

import static java.util.Arrays.asList;

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

		List<Branch> neededBranches = ParameterInjectionSupport.computeBranches(context, requiredParameters);

		return neededBranches.stream().flatMap(branch -> {
			var valuesByInjector = new LinkedHashMap<Injector, List<?>>();
			requiredParameters.forEach(p -> {
				Injector injector = branch.injectorFor(p);
				if (injector != null) {
					valuesByInjector.computeIfAbsent(injector, key -> branch.toInject().get(key).values());
				}
			});

			List<Injector> injectors = List.copyOf(valuesByInjector.keySet());
			List<List<Object>> combinations = ParameterInjectionSupport.cartesianProduct(valuesByInjector.values());

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

}
