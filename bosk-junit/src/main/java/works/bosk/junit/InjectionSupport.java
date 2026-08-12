package works.bosk.junit;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
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
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

/**
 * Shared support for injection at method and class level.
 */
class InjectionSupport {
	static final ExtensionContext.Namespace NAMESPACE =
		ExtensionContext.Namespace.create(InjectionSupport.class);
	static final String BRANCH_KEY = "branch";

	/**
	 * Compute branches for class-level injected fields.
	 */
	static List<Branch> computeBranchesForFields(ExtensionContext context) {
		Branch startingBranch = Branch.empty(); // Class-level injection starts from scratch
		return computeBranches(context, getInjectedFields(context), startingBranch, (branch, element) ->
			branch.keyForField((Field) element));
	}

	/**
	 * Compute branches for method parameters.
	 */
	static List<Branch> computeBranchesForParameters(ExtensionContext context, List<Parameter> requiredParameters, Branch startingBranch) {
		return computeBranches(context, requiredParameters, startingBranch, (branch, element)
			-> branch.keyForParameter((Parameter) element));
	}

	/**
	 * Determine what branches are needed to provide all the injectors required
	 * directly or indirectly by the {@code requiredElements}.
	 * <p>
	 * This is done in two phases.
	 * First, we compute all possible branches by instantiating all injector classes.
	 * Once we have the injectors, we can use {@link Injector#supports}
	 * to determine which ones are needed,
	 * and do a second pass to compute the branches for just those injectors.
	 * <p>
	 * The returned list contains the combinations of injector values necessary
	 * to provide every element in {@code elements}, with all injectors in the correct order.
	 *
	 * @param context the JUnit extension context for the current test
	 * @param requiredElements the annotated elements (parameters or fields) to be injected
	 * @param startingBranch the branch to start from; for field-level use {@link Branch#empty()}
	 * @param keyResolver a function that returns the {@link InjectionKey} for an element on a branch
	 * @return the list of branches that need to be executed to satisfy the elements
	 * @throws ParameterResolutionException if a required injector or dependency is not present
	 */
	private static List<Branch> computeBranches(
		ExtensionContext context,
		List<? extends AnnotatedElement> requiredElements,
		Branch startingBranch,
		BiFunction<Branch, AnnotatedElement, InjectionKey> keyResolver
	) {
		// We have a chicken-and-egg thing happening here:
		// we can't know which injectors we need until we call Injector.supportsParameter,
		// which we can't do until we've instantiated the injectors.
		//
		// So, we do an initial pass assuming we'll need them all,
		// with all possible dimension names:

		List<String> allDimensionNames = distinctDimensionNamesFor(requiredElements);
		List<Branch> allPossibleBranches = List.of(startingBranch);
		var allValueSources = getValueSources(context);
		for (var source : allValueSources) {
			for (var dimensionName: allDimensionNames) {
				allPossibleBranches = expandedBranches(allPossibleBranches, new Dependency(source, dimensionName));
			}
		}

		if (allPossibleBranches.isEmpty()) {
			// allPossibleBranches started off with startingBranch and is now empty.
			// This can only happen if some injector decided to inject no values,
			// which means there are no combinations to test.
			return List.of();
		}

		// At this stage, we have a large list of branches for every possible
		// InjectionKey we could possibly have needed, but now we know more:
		// we can determine which ones were actually used and for what,
		// now that we have every injector object we could possibly need.
		//
		// If we don't prune out the unneeded injectors,
		// we will end up calling the test method with the same parameters
		// multiple times, varying only the values of parameters
		// that aren't even used.
		//
		// Let's determine which injection keys we actually needed.
		// We can do this by picking any Branch (they all have the same injection keys)
		// and seeing which injectors were needed
		// to provide values for the requiredParameters.

		Branch someBranch = allPossibleBranches.getFirst();

		// Collect direct dependencies required by the elements.
		var requiredDeps = new LinkedHashSet<Dependency>();
		for (var e : requiredElements) {
			var key = keyResolver.apply(someBranch, e);
			if (key == null) {
				throw new ParameterResolutionException("No injector for " + e);
			}
			requiredDeps.add(key.dependency());
		}

		var unorderedDependencies = new HashSet<Dependency>();
		someBranch.toInject.forEach((key, superposition) -> {
			Dependency dep = key.dependency();
			if (requiredDeps.contains(dep)) {
				unorderedDependencies.add(dep);
				unorderedDependencies.addAll(superposition.provenance());
			}
		});

		// Final dependency list in the correct order (which is defined by someBranch.toInject)
		List<Dependency> dependencies = new ArrayList<>();
		for (var key : someBranch.toInject.keySet()) {
			var dep = key.dependency();
			if (unorderedDependencies.contains(dep)) {
				dependencies.add(dep);
			}
		}

		// Finally, we can recalculate the branches a second time,
		// expanding only the injector classes known to be needed.

		List<Branch> neededBranches = List.of(startingBranch);
		for (var dependency: dependencies) {
			neededBranches = expandedBranches(neededBranches, dependency);
		}
		return neededBranches;
	}

	/**
	 * The value sources available to a test class, in the order they should be
	 * expanded. For each class in the hierarchy (superclass first), the sources
	 * declared by its {@link InjectFrom} annotations.
	 *
	 * @return the value sources in the order they should be expanded
	 */
	static List<ValueSource> getValueSources(ExtensionContext context) {
		List<Class<?>> bottomUp = new ArrayList<>();
		for (var c = context.getRequiredTestClass(); c != Object.class; c = c.getSuperclass()) {
			bottomUp.add(c);
		}
		List<ValueSource> result = new ArrayList<>();
		for (var c : bottomUp.reversed()) {
			for (var a: c.getAnnotationsByType(InjectFrom.class)) {
				for (var injectorClass : a.value()) {
					result.add(valueSourceFor(injectorClass));
				}
			}
		}
		return result;
	}

	/**
	 * A value source: either an {@link Injector} class or an enum, whose values
	 * are injected. A source serves sites of any dimension; each site dimension
	 * creates a separate {@link Dependency}.
	 */
	sealed interface ValueSource permits InjectorSource, EnumSource {}

	record InjectorSource(Class<?> injectorClass) implements ValueSource {}

	record EnumSource(Class<?> enumClass) implements ValueSource {}

	/**
	 * @throws ParameterResolutionException if {@code injectorClass} is neither an
	 * enum nor an {@link Injector}
	 */
	private static ValueSource valueSourceFor(Class<?> injectorClass) {
		if (injectorClass.isEnum()) {
			return new EnumSource(injectorClass);
		}
		if (Injector.class.isAssignableFrom(injectorClass)) {
			return new InjectorSource(injectorClass);
		}
		throw new ParameterResolutionException(
			"Unsupported injector class: "
				+ injectorClass
				+ "; accepted injector types are enum or Injector");
	}

	/**
	 * A version of {@link Branch#expandedFor(Dependency)} that operates on a list.
	 */
	private static List<Branch> expandedBranches(List<Branch> currentBranches, Dependency dependency) {
		List<Branch> expanded = new ArrayList<>();
		for (Branch branch : currentBranches) {
			expanded.addAll(branch.expandedFor(dependency));
		}
		return unmodifiableList(expanded);
	}

	/**
	 * A possible future in which certain values are chosen for injection.
	 * <p>
	 * Because parameters can be injected into the injectors themselves,
	 * the parameters are not fully independent of each other,
	 * and so a straightforward cartesian product of all parameter values doesn't work.
	 * A {@code Branch} represents one "scenario" for the injectors,
	 * within which the full cartesian product expansion of parameter values is valid.
	 * <p>
	 * Uses a quantum "many worlds" metaphor to describe possible futures.
	 * Specifically: when one injector injects into another,
	 * we will need multiple instances of the latter injector,
	 * and that is what leads to multiple branches.
	 * On each branch, the providing injector injects a single value,
	 * so "the wavefunction has collapsed" on that branch,
	 * and the providing injector is treated as though it provided just a single value.
	 * <p>
	 * During the instantiation of injectors, the branch may be "incomplete" in the sense
	 * that it contains entries for only the first N {@code InjectionKey}s.
	 *
	 * @param toInject A map from each {@link InjectionKey} to the list of values associated with that key on this branch.
	 *                 For cases where an injector has already had its constructor parameters supplied by earlier injectors,
	 *                 the map will contain just the single value used to construct that injector on this branch.
	 */
	record Branch(
		Map<InjectionKey, Superposition> toInject
	) {
		static Branch empty() {
			return new Branch(Map.of());
		}

		/**
		 * Computes a list of branches on which the source identified by
		 * {@code dependency} has been instantiated; if the source has
		 * parameters, the resulting list will have one branch per combination
		 * of parameter values; otherwise, it's a singleton list.
		 * <p>
		 * The resulting branches all have {@link InjectionKey}s suitable to inject
		 * values for the given source and {@code dimensionName}.
		 */
		List<Branch> expandedFor(Dependency dependency) {
			boolean alreadyExists = toInject.values().stream() // TODO: A more efficient data structure
				.anyMatch(s -> s.provenance().contains(dependency));
			if (alreadyExists) {
				// The branch has already been expanded for this dependency.
				// If we expand it again, we risk introducing combinations that aren't supposed to be there.
				return List.of(this);
			}

			return switch (dependency.source()) {
				case InjectorSource injectorSource -> expandedForInjector(injectorSource, dependency.dimensionName());
				case EnumSource enumSource -> expandedForEnum(enumSource, dependency.dimensionName());
			};
		}

		private @NonNull List<Branch> expandedForInjector(InjectorSource source, String dimensionName) {
			Class<?> injectorType = source.injectorClass();

			// Determine the injection requirements of injectorType's constructor
			Constructor<?>[] ctors = injectorType.getDeclaredConstructors();
			if (ctors.length != 1) {
				throw new ParameterResolutionException("Injector class must have exactly one constructor: " + injectorType);
			}
			var ctor = ctors[0];
			setAccessible(ctor);

			// Determine how the parameters are to be injected
			List<InjectionKey> ctorKeys = Arrays.stream(ctor.getParameters())
				.map(p -> {
					InjectionKey key = keyForParameter(p);
					if (key == null) {
						throw new IllegalStateException("Error calling constructor on injector class " + injectorType + ": no injector found for parameter " + p);
					} else {
						return key;
					}
				})
				.distinct() // Two parameters can use the same key
				.toList();

			var provenance = new HashSet<Dependency>();
			ctorKeys.forEach(ctorKey -> {
				provenance.addAll(toInject.get(ctorKey).provenance());
				provenance.add(ctorKey.dependency());
			});
			provenance.add(new Dependency(source, dimensionName));

			List<List<?>> ctorArgLists = new ArrayList<>();
			for (InjectionKey ctorKey : ctorKeys) {
				ctorArgLists.add(toInject.get(ctorKey).values());
			}

			// Instantiate an injector for each combination of constructor arguments
			// and add the corresponding branch to the result list.
			List<Branch> result = new ArrayList<>();
			for (List<Object> ctorArgs : cartesianProduct(ctorArgLists)) {
				try {
					// Collapse superpositions for the constructor args we've chosen
					var toInject = new LinkedHashMap<>(this.toInject);
					for (int i = 0; i < ctorKeys.size(); i++) {
						Object ctorArgValue = ctorArgs.get(i);
						toInject.computeIfPresent(ctorKeys.get(i), (_, s) -> s.collapsed(ctorArgValue));
					}

					// Add our new injector
					var injector = instantiateInjector(ctor, ctorKeys, ctorArgs);
					toInject.put(
						new InjectionKey(injector, dimensionName),
						new Superposition(injector.values(), provenance)
					);

					result.add(new Branch(unmodifiableMap(toInject)));
				} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
					throw new ParameterResolutionException("Error calling constructor on injector class " + injectorType, e);
				}
			}
			return result;
		}

		/**
		 * Enum injectors are much simpler than general {@link Injector}s.
		 * They have no constructor arguments, support only their own class,
		 * and return all the enum values.
		 * They're orthogonal to other injectors, so they combine as a
		 * cartesian product, so "expansion" actually just returns one {@link Branch}.
		 */
		private @NonNull List<Branch> expandedForEnum(EnumSource source, String dimensionName) {
			Class<?> enumClass = source.enumClass();
			var enumInjector = new EnumValueInjector(enumClass, List.of((Object[]) enumClass.getEnumConstants()));

			var toInject = new LinkedHashMap<>(this.toInject);
			toInject.put(
				new InjectionKey(enumInjector, dimensionName),
				new Superposition(enumInjector.values(), Set.of(new Dependency(source, dimensionName)))
			);
			return List.of(new Branch(unmodifiableMap(toInject)));
		}

		/**
		 * @param ctorArgValues a parallel list to {@code ctorKeys} indicating which value to use for each {@link InjectionKey}.
		 */
		private @NonNull Injector instantiateInjector(Constructor<?> ctor, List<InjectionKey> ctorKeys, List<Object> ctorArgValues) throws InstantiationException, IllegalAccessException, InvocationTargetException {
			// ctorArgValues has one entry per InjectionKey, but we need one entry per argument.
			// These differ if the constructor uses the same InjectionKey twice.
			// It's not clear why a user would want that, but the semantics are well-defined, so we must support it.
			Object[] args = new Object[ctor.getParameterCount()];
			int argIndex = 0;
			for (var p: ctor.getParameters()) {
				var key = keyForParameter(p);
				var ctorKeyIndex = ctorKeys.indexOf(key);
				assert ctorKeyIndex >= 0: "Internal error: injector not found for parameter " + p + " of constructor " + ctor;
				args[argIndex++] = ctorArgValues.get(ctorKeyIndex);
			}
			return (Injector) ctor.newInstance(args);
		}

		@Nullable
		InjectionKey keyForParameter(Parameter p) {
			return keyFor(p, p.getType());
		}

		@Nullable
		InjectionKey keyForField(Field f) {
			return keyFor(f, f.getType());
		}

		@Nullable
		InjectionKey keyFor(AnnotatedElement element, Class<?> elementType) {
			String dimensionName = dimensionNameFor(element);
			return List.copyOf(toInject.keySet())
				.reversed()
				.stream()
				.filter(k -> k.dimensionName().equals(dimensionName))
				.filter(k -> k.injector().supports(element, elementType))
				.findFirst()
				.orElse(null);
		}

		Branch withFieldValues(Map<Field, Object> fieldValues) {
			var newMap = new LinkedHashMap<>(toInject);
			for (var entry : fieldValues.entrySet()) {
				var key = keyForField(entry.getKey());
				if (key == null) {
					throw new ParameterResolutionException("No injector for field " + entry.getKey());
				}
				Superposition existing = newMap.get(key);
				assert existing != null;
				newMap.put(key, existing.collapsed(entry.getValue()));
			}
			return new Branch(newMap);
		}

		List<?> valuesFor(InjectionKey key) {
			Superposition s = toInject.get(key);
			if (s == null) {
				// key has no values yet on this branch.
				// Pull a fresh list from its injector.
				return key.injector().values();
			} else {
				return s.values();
			}
		}

		@Override
		public String toString() {
			return toInject.toString();
		}
	}

	private static @NonNull String dimensionNameFor(AnnotatedElement element) {
		var injected = element.getAnnotation(Injected.class);
		return injected == null ? "" : injected.value();
	}

	private static @NonNull List<String> distinctDimensionNamesFor(List<? extends AnnotatedElement> elements) {
		return elements.stream()
			.map(InjectionSupport::dimensionNameFor)
			.distinct()
			.toList();
	}

	/**
	 * Identifies a dimension: the injector instance serving values on a
	 * dimension name, in a {@link Branch}'s {@code toInject} map. The injector
	 * instance is what actually supplies values and tests whether a field or
	 * parameter can be served.
	 * <p>
	 * Two fields or parameters that use the same InjectionKey always receive
	 * the same value; those with different InjectionKeys receive combinations
	 * of values.
	 *
	 * @param injector the injector instance providing values for this key
	 * @param dimensionName the dimension name; the empty string denotes the unnamed dimension
	 */
	record InjectionKey(Injector injector, String dimensionName) {
		Dependency dependency() {
			if (injector instanceof EnumValueInjector enumValueInjector) {
				return new Dependency(new EnumSource(enumValueInjector.injectorClass()), dimensionName);
			}
			return new Dependency(new InjectorSource(injector.injectorClass()), dimensionName);
		}

		@Override
		public String toString() {
			if ("".equals(dimensionName)) {
				return injector.toString();
			} else {
				return injector + "@" + dimensionName;
			}
		}
	}

	/**
	 * Represents the possible values to be injected for a particular {@link InjectionKey}
	 * on a particular {@link Branch}.
	 * In other words: given how injection decisions already made earlier on the
	 * branch affect constructor parameters of other injectors,
	 * this represents the values to be injected for a particular {@link InjectionKey}.
	 *
	 * @param values the subset of {@link Injector#values()} to be injected in this scenario
	 * @param provenance the prerequisites required, directly or indirectly,
	 *                   to produce these values, with no guarantees on the order
	 */
	record Superposition(
		List<?> values,
		Set<Dependency> provenance
	){
		Superposition collapsed(Object singleValue) {
			return new Superposition(List.of(singleValue), provenance);
		}

		@Override
		public String toString() {
			// Provenance is a bit much during debugging
			return values.toString();
		}
	}

	/**
	 * A dimension: a {@link ValueSource} paired with a dimension name.
	 * Dependencies are used for provenance: to detect that a source has already
	 * been expanded on a branch, and to decide which sources to re-expand in
	 * the second pass.
	 */
	record Dependency(ValueSource source, String dimensionName) {}

	@SuppressForbidden("Only for testing code; we have few other options here")
	static void setAccessible(AccessibleObject accessibleObject) {
		accessibleObject.setAccessible(true);
	}

	/**
	 * Compute the cartesian product of a list of lists.
	 */
	static List<List<Object>> cartesianProduct(Collection<? extends List<?>> input) {
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
	 * @return the fields annotated with {@link Injected} in the class hierarchy
	 */
	static List<Field> getInjectedFields(ExtensionContext context) {
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
