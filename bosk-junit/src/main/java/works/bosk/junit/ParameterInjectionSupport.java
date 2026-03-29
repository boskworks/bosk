package works.bosk.junit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

/**
 * Shared support for parameter injection at method and class level.
 */
public class ParameterInjectionSupport {
	/**
	 * Determine what branches are needed to provide all the injectors required
	 * directly or indirectly by the {@code requiredParameters}.
	 * <p>
	 * This is done in two phases.
	 * First, we compute all possible branches by instantiating all injector classes.
	 * Once we have the injectors, we can use {@link Injector#supportsParameter}
	 * to determine which ones are needed,
	 * and do a second pass to compute the branches for just those injectors.
	 *
	 * @see Branch
	 */
	public static List<Branch> computeBranches(ExtensionContext context, List<Parameter> requiredParameters) {
		return computeBranches(context, requiredParameters, Branch.empty());
	}

	/**
	 * Compute branches starting from an existing branch (e.g., from class-level injection).
	 */
	public static List<Branch> computeBranches(ExtensionContext context, List<Parameter> requiredParameters, Branch startingBranch) {
		List<Branch> allPossibleBranches = List.of(startingBranch);
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

		var neededInjectorClasses = new HashSet<Class<? extends Injector>>();
		Branch someBranch = allPossibleBranches.getFirst();
		requiredParameters.forEach(p -> getNeededInjectorClasses(p, someBranch, neededInjectorClasses));

		// And now we can recalculate the branch list, expanding only the required injector classes.
		// Start from startingBranch and only add NEW injectors that aren't already in it.
		List<Branch> neededBranches = List.of(startingBranch);

		for (var injectorClass : allInjectorClasses) { // The order matters here
			if (neededInjectorClasses.contains(injectorClass)) {
				// Only expand if this injector class is NOT already in startingBranch
				boolean alreadyInStarting = startingBranch.toInject().keySet()
					.stream().anyMatch(i -> i.getClass() == injectorClass);

				if (!alreadyInStarting) {
					neededBranches = expandedBranches(neededBranches, injectorClass);
				}
			}
		}
		return neededBranches;
	}

	/**
	 * @return the injector classes in the order they should be instantiated
	 */
	public static List<Class<? extends Injector>> getAllInjectorClasses(ExtensionContext context) {
		List<Class<?>> bottomUp = new ArrayList<>();
		for (var c = context.getRequiredTestClass(); c != Object.class; c = c.getSuperclass()) {
			bottomUp.add(c);
		}
		List<Class<? extends Injector>> allInjectors = new ArrayList<>();
		for (var c : bottomUp.reversed()) {
			for (var a: c.getAnnotationsByType(InjectFrom.class)) {
				allInjectors.addAll(asList(a.value()));
			}
		}
		return allInjectors;
	}

	private static void getNeededInjectorClasses(
		Parameter param,
		Branch branch,
		Set<Class<? extends Injector>> needed
	) {
		Injector pi = branch.injectorFor(param);
		if (pi != null && needed.add(pi.getClass())) {
			var provenance = branch.toInject.get(pi).provenance();
			var list = provenance.stream()
				.map(Injector::getClass)
				.toList();
			needed.addAll(list);
		}
	}

	/**
	 * Expand each branch by instantiating all injectors of the given type.
	 */
	private static List<Branch> expandedBranches(List<Branch> currentBranches, Class<? extends Injector> injectorType) {
		List<Branch> expanded = new ArrayList<>();
		for (Branch branch : currentBranches) {
			expanded.addAll(branch.withInjectors(injectorType));
		}
		return expanded;
	}

	/**
	 * @param values the subset of {@link Injector#values()} to be injected in this scenario
	 * @param provenance the set of injectors required, directly or indirectly,
	 *                   to produce these values, with no guarantees on the order
	 */
	public record Superposition(
		List<?> values,
		Set<Injector> provenance
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
	public record Branch(
		Map<Injector, Superposition> toInject
	) {
		public static Branch empty() {
			return new Branch(Map.of());
		}

		public List<Branch> withInjectors(Class<? extends Injector> injectorType) {
			Constructor<?>[] ctors = injectorType.getDeclaredConstructors();
			if (ctors.length != 1) {
				throw new ParameterResolutionException("Injector class must have exactly one constructor: " + injectorType);
			}
			var ctor = ctors[0];
			ctor.setAccessible(true);
			List<Injector> injectorsToUse = Arrays.stream(ctor.getParameters())
				.map(this::injectorFor)
				.filter(Objects::nonNull)
				.distinct()
				.toList();

			List<? extends List<?>> valueLists = injectorsToUse.stream()
				.map(toInject::get)
				.map(Superposition::values)
				.toList();

			List<Branch> result = new ArrayList<>();
			for (List<Object> combos : cartesianProduct(valueLists)) {
				try {
					List<Object> args = new ArrayList<>();
					var injectorsUsed = new LinkedHashSet<Injector>();
					for (var p: ctor.getParameters()) {
						var pi = injectorFor(p);
						injectorsUsed.add(pi);
						var index = injectorsToUse.indexOf(pi);
						assert index >= 0: "Internal error: injector not found for parameter " + p + " of constructor " + ctor;
						args.add(combos.get(index));
					}

					var injector = (Injector) ctor.newInstance(args.toArray());

					var provenance = new HashSet<Injector>();
					injectorsUsed.forEach(pi -> {
						provenance.add(pi);
						provenance.addAll(toInject.get(pi).provenance());
					});
					provenance.add(injector);

					var map = new LinkedHashMap<>(this.toInject);
					map.put(injector, new Superposition(injector.values(), provenance));

					int i = 0;
					for (var pi: injectorsToUse) {
						var possibleValues = toInject.get(pi).values();
						if (possibleValues.size() >= 2) {
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

		@Nullable
		public Injector injectorFor(Parameter p) {
			return List.copyOf(toInject.entrySet())
				.reversed()
				.stream()
				.filter(e -> e.getKey().supportsParameter(p))
				.findFirst()
				.map(Map.Entry::getKey)
				.orElse(null);
		}

		@Nullable
		public Injector injectorForField(Field f) {
			return List.copyOf(toInject.entrySet())
				.reversed()
				.stream()
				.filter(e -> e.getKey().supportsField(f))
				.findFirst()
				.map(Map.Entry::getKey)
				.orElse(null);
		}

		public Branch withFieldValues(Map<Field, Object> fieldValues) {
			var newMap = new LinkedHashMap<>(toInject);
			for (var entry : fieldValues.entrySet()) {
				Injector injector = injectorForField(entry.getKey());
				Superposition existing = toInject.get(injector);
				newMap.put(injector, existing.collapsed(entry.getValue()));
			}
			return new Branch(newMap);
		}

		@Override
		public String toString() {
			return toInject.entrySet().stream()
				.map(e -> e.getKey().getClass().getSimpleName() + "=" + e.getValue().values())
				.collect(joining(", ", "Branch{", "}"));
		}
	}

	/**
	 * Compute the cartesian product of a list of lists.
	 */
	public static List<List<Object>> cartesianProduct(Collection<? extends List<?>> input) {
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
	 * Expand each branch for class-level injection.
	 * <p>
	 * This uses the same logic as method-level injection ({@link #computeBranches}).
	 * The cartesian product expansion happens at invocation time rather than here,
	 * allowing uniform handling of both fields and parameters.
	 */
	public static List<Branch> expandBranchesForClassLevel(List<Branch> currentBranches, Class<? extends Injector> injectorType) {
		List<Branch> expanded = new ArrayList<>();
		for (Branch branch : currentBranches) {
			expanded.addAll(branch.withInjectors(injectorType));
		}
		return expanded;
	}

	/**
	 * @return true if the injector class supports at least one of the given fields.
	 *
	 * Note: This method cannot determine field support for injectors with constructor dependencies,
	 * since we don't have the injector values available at this point.
	 * In such cases, we conservatively return true if the fields list is non-empty,
	 * allowing the injector to be instantiated and checked later.
	 */
	@SuppressWarnings("unchecked")
	public static boolean supportsAnyField(Class<? extends Injector> injectorClass, List<Field> fields) {
		try {
			var injector = injectorClass.getDeclaredConstructors()[0];
			injector.setAccessible(true);
			var params = injector.getParameters();

			if (params.length > 0) {
				// Can't instantiate without dependencies, so assume it might support fields
				return !fields.isEmpty();
			}
			var injectorInstance = (Injector) injector.newInstance();
			for (Field f : fields) {
				if (injectorInstance.supportsField(f)) {
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			throw new ParameterResolutionException("Error checking field support for " + injectorClass, e);
		}
	}

	/**
	 * @return the fields annotated with {@link Injected} in the class hierarchy
	 */
	public static List<Field> getInjectedFields(ExtensionContext context) {
		List<Field> fields = new ArrayList<>();
		for (var c = context.getRequiredTestClass(); c != Object.class; c = c.getSuperclass()) {
			for (var field : c.getDeclaredFields()) {
				if (field.isAnnotationPresent(Injected.class)) {
					fields.add(field);
				}
			}
		}
		return fields;
	}

}
