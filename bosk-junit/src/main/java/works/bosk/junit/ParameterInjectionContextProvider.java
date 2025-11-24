package works.bosk.junit;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

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

		List<Branch> neededBranches = computeBranches(context, requiredParameters);

		return neededBranches.stream().flatMap(branch -> {
			var valuesByInjector = new LinkedHashMap<ParameterInjector, List<?>>();
			requiredParameters.forEach(p -> {
				ParameterInjector injector = branch.injectorFor(p);
				if (injector != null) {
					valuesByInjector.computeIfAbsent(injector, key -> branch.toInject.get(key).values());
				}
			});

			List<ParameterInjector> injectors = List.copyOf(valuesByInjector.keySet());
			List<List<Object>> combinations = cartesianProduct(valuesByInjector.values());

			return combinations.stream().map(combo -> {
				// Swizzle the combo into a useful map from parameter to value
				var paramValueMap = new LinkedHashMap<Parameter, Object>();
				requiredParameters.forEach(parameter -> {
					// TODO: There's essentially a copy of this in Branch.withInjectors
					ParameterInjector pi = branch.injectorFor(parameter);
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

	/**
	 * Determine what branches are needed to provide all the injectors required
	 * directly or indirectly by the {@code requiredParameters}.
	 * <p>
	 * This is done in two phases.
	 * First, we compute all possible branches by instantiating all injector classes.
	 * Once we have the injectors, we can use {@link ParameterInjector#supportsParameter}
	 * to determine which ones are needed,
	 * and do a second pass to compute the branches for just those injectors.
	 *
	 * @see Branch
	 */
	private List<Branch> computeBranches(ExtensionContext context, List<Parameter> requiredParameters) {
		List<Branch> allPossibleBranches = List.of(Branch.empty());
		var allInjectorClasses = getAllInjectorClasses(context);
		for (var injectorClass : allInjectorClasses) {
			allPossibleBranches = expandedBranches(allPossibleBranches, injectorClass);
		}

		if (allPossibleBranches.isEmpty()) {
			return List.of();
		}

		// At this stage, we have a list of branches that have instantiated
		// all the injectors we could possibly have needed for any parameter,
		// but some injectors might be for parameters that aren't used
		// by the test method or the class constructor.
		//
		// If we don't prune out the unneeded injectors,
		// we will end up calling the test method with the same parameters
		// multiple times, varying only the values of parameters
		// that aren't even used.
		//
		// Let's determine which injector classes we actually needed.
		// We can do this by picking any Branch (they all have the same types of injectors)
		// and seeing which injectors are needed
		// to provide values for the requiredParameters.

		var neededInjectorClasses = new HashSet<Class<? extends ParameterInjector>>();
		Branch someBranch = allPossibleBranches.getFirst();
		requiredParameters.forEach(p -> getNeededInjectorClasses(p, someBranch, neededInjectorClasses));

		// And now we can recalculate the branch list, expanding only the required injector classes
		List<Branch> neededBranches = List.of(Branch.empty());
		for (var injectorClass : allInjectorClasses) { // The order matters here
			if (neededInjectorClasses.contains(injectorClass)) {
				neededBranches = expandedBranches(neededBranches, injectorClass);
			}
		}
		return neededBranches;
	}

	/**
	 * @return the injector classes in the order they should be instantiated
	 */
	private static List<Class<? extends ParameterInjector>> getAllInjectorClasses(ExtensionContext context) {
		List<Class<?>> bottomUp = new ArrayList<>();
		for (var c = context.getRequiredTestClass(); c != Object.class; c = c.getSuperclass()) {
			bottomUp.add(c);
		}
		List<Class<? extends ParameterInjector>> allInjectors = new ArrayList<>();
		for (var c : bottomUp.reversed()) {
			for (var a: c.getAnnotationsByType(InjectFrom.class)) {
				allInjectors.addAll(asList(a.value()));
			}
		}
		return allInjectors;
	}

	private void getNeededInjectorClasses(
		Parameter param,
		Branch branch,
		Set<Class<? extends ParameterInjector>> needed
	) {
		ParameterInjector pi = branch.injectorFor(param);
		if (pi != null && needed.add(pi.getClass())) {
			var provenance = branch.toInject.get(pi).provenance();
			var list = provenance.stream()
				.map(ParameterInjector::getClass)
				.toList();
			needed.addAll(list);
		}
	}

	/**
	 * Expand each branch by instantiating all injectors of the given type.
	 */
	private List<Branch> expandedBranches(List<Branch> currentBranches,
										  Class<? extends ParameterInjector> injectorType) {
		List<Branch> expanded = new ArrayList<>();
		for (Branch branch : currentBranches) {
			expanded.addAll(branch.withInjectors(injectorType));
		}
		return expanded;
	}

	/**
	 * Compute the cartesian product of a list of lists.
	 */
	private static List<List<Object>> cartesianProduct(Collection<? extends List<?>> input) {
		List<List<Object>> result = List.of(List.of());
		for (List<?> list : input) {
			result = result.stream()
				.flatMap(prev -> list.stream().map(v -> {
					List<Object> next = new ArrayList<>(prev);
					next.add(v);
					return next;
				}))
				.toList();
		}
		return result;
	}

	/**
	 * @param values the subset of {@link ParameterInjector#values()} to be injected in this scenario
	 * @param provenance the set of injectors required, directly or indirectly,
	 *                   to produce these values, with no guarantees on the order
	 */
	record Superposition(
		List<?> values,
		Set<ParameterInjector> provenance
	){
		Superposition collapsed(Object singleValue) {
			return new Superposition(List.of(singleValue), provenance);
		}
	}

	/**
	 * A list of injectors that have been instantiated so far.
	 * <p>
	 * Because parameters can be injected into the injectors themselves,
	 * the parameters are not fully independent of each other,
	 * and so a straightforward cartesian product of all parameter values doesn't work.
	 * A {@code Branch} represents one "scenario" for the injectors,
	 * within which cartesian product expansion of parameter values is valid.
	 * <p>
	 * During the instantiation of injectors, the branch may be "incomplete" in the sense
	 * that it contains injectors for only the first N injector classes.
	 *
	 * @param toInject A map from each injector to the list of values it provided on this branch.
	 *                 For injectors that have already provided values for constructor parameters of other injectors,
	 *                 this map will contain just the one value used to construct that injector on this branch.
	 */
	record Branch(
		Map<ParameterInjector, Superposition> toInject
	) {
		static Branch empty() {
			return new Branch(Map.of());
		}

		List<Branch> withInjectors(Class<? extends ParameterInjector> injectorType) {
			Constructor<?>[] ctors = injectorType.getDeclaredConstructors();
			if (ctors.length != 1) {
				throw new ParameterResolutionException("Injector class must have exactly one constructor: " + injectorType);
			}
			var ctor = ctors[0];
			ctor.setAccessible(true);
			List<ParameterInjector> injectorsToUse = Arrays.stream(ctor.getParameters())
				.map(this::injectorFor)
				.filter(Objects::nonNull)
				.distinct()
				.toList();

			// (Recall that, for an injector that has already provided a value
			// for a constructor parameter of another injector on this branch,
			// its corresponding list will contain just that one value
			// and will not further expand the cartesian product.)
			List<? extends List<?>> valueLists = injectorsToUse.stream()
				.map(toInject::get)
				.map(Superposition::values)
				.toList();

			List<Branch> result = new ArrayList<>();
			for (List<Object> combos : cartesianProduct(valueLists)) {
				try {
					List<Object> args = new ArrayList<>();
					var injectorsUsed = new LinkedHashSet<ParameterInjector>();
					for (var p: ctor.getParameters()) {
						var pi = injectorFor(p);
						injectorsUsed.add(pi);
						var index = injectorsToUse.indexOf(pi);
						assert index >= 0: "Internal error: injector not found for parameter " + p + " of constructor " + ctor;
						args.add(combos.get(index));
					}

					// Instantiate the injector
					var injector = (ParameterInjector) ctor.newInstance(args.toArray());

					// Compute the provenance
					var provenance = new HashSet<ParameterInjector>();
					injectorsUsed.forEach(pi -> {
						provenance.add(pi);
						provenance.addAll(toInject.get(pi).provenance());
					});
					provenance.add(injector);

					// Compute the new toInject map
					var map = new LinkedHashMap<>(this.toInject);
					map.put(injector, new Superposition(injector.values(), provenance));

					// Collapse the wavefunction for injectors that provided a value
					int i = 0;
					for (var pi: injectorsToUse) {
						var possibleValues = toInject.get(pi).values();
						if (possibleValues.size() >= 2) {
							// pi has provided a value on this branch.
							// Record that fact for future uses of the same injector.
							map.put(pi, map.get(pi).collapsed(args.get(i)));
						}
						++i;
					}
					result.add(new Branch(map));
				} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
					throw new ParameterResolutionException("Error calling constructor on injector class " + injectorType, e);
				}
			}
			return result;
		}

		/**
		 * @return null if there's no injector, indicating that the parameter must be resolved some other way
		 */
		ParameterInjector injectorFor(Parameter p) {
			return List.copyOf(toInject.entrySet())
				.reversed()
				.stream()
				.filter(e -> e.getKey().supportsParameter(p))
				.findFirst()
				.map(Map.Entry::getKey)
				.orElse(null);
		}
	}
}
