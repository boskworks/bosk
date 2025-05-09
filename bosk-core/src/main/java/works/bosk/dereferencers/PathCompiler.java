package works.bosk.dereferencers;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDriver;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.Path;
import works.bosk.Phantom;
import works.bosk.Reference;
import works.bosk.ReferenceUtils;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.bytecode.LocalVariable;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NotYetImplementedException;

import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.permuteArguments;
import static java.util.Collections.synchronizedList;
import static java.util.Collections.synchronizedMap;
import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PRIVATE;
import static works.bosk.Path.isParameterSegment;
import static works.bosk.ReferenceUtils.getterMethod;
import static works.bosk.ReferenceUtils.gettersForConstructorParameters;
import static works.bosk.ReferenceUtils.parameterType;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.bytecode.ClassBuilder.here;
import static works.bosk.util.ReflectionHelpers.boxedClass;

/**
 * Compiles {@link Path} objects into {@link Dereferencer}s for a given source {@link Type}.
 *
 * <p>
 * <em>Implementation note:</em> This class has some pretty deep inner-class nesting, because
 * a lot of these classes need context from their outer class. Some could be written another way;
 * others really can't. Whether you find this objectionable depends on your level of distaste
 * for inner classes.
 */
@RequiredArgsConstructor(access = PRIVATE)
public final class PathCompiler {
	private final Type sourceType;
	private final Map<Path, DereferencerBuilder> memoizedBuilders = synchronizedMap(new WeakHashMap<>());
	private final Map<DereferencerBuilder, Dereferencer> memoizedDereferencers = synchronizedMap(new WeakHashMap<>());

	/**
	 * The weak hashmaps are a bit too weak. We can't use normal maps because there
	 * could be an unlimited number of different Paths, so that would be a memory leak.
	 * But if the builders for parameterized paths get collected, then they won't be
	 * reused as they should be, and performance could suffer dramatically due to
	 * unnecessary compilation and class loading overhead.
	 *
	 * <p>
	 * As a compromise, we keep alive all fully parameterized paths here to make
	 * sure their dereferencers don't get collected. In the absence of recursive
	 * data structures, any one bosk has only a finite variety of possible fully
	 * parameterized paths, so this list has a bounded size.
	 */
	private final List<Path> keepAliveFullyParameterizedPaths = pathList();

	private static final Map<Type, PathCompiler> compilersByType = new ConcurrentHashMap<>();

	public static PathCompiler withSourceType(Type sourceType) {
		/* We instantiate just one PathCompiler per sourceType.

		PathCompiler takes a few seconds to warm up because of all
		the class loading.  This generally doesn't matter in
		production, because you have a small number of Bosks
		(usually just one) and once it warms up, it's fast.
		But for unit tests, we make hundreds of Bosks, so sharing
		their PathCompilers makes the tests much, much faster.
		 */
		return compilersByType.computeIfAbsent(sourceType, PathCompiler::new);
	}

	public Dereferencer compiled(Path path) throws InvalidTypeException {
		return memoizedDereferencers.computeIfAbsent(builderFor(path), DereferencerBuilder::buildInstance);
	}

	public Path fullyParameterizedPathOf(Path path) throws InvalidTypeException {
		return builderFor(path).fullyParameterizedPath();
	}

	public Type targetTypeOf(Path path) throws InvalidTypeException {
		return builderFor(path).targetType();
	}

	private DereferencerBuilder builderFor(Path path) throws InvalidTypeException {
		// We'd like to use computeIfAbsent for this, but we can't,
		// because in some cases we need to add two entries to the map
		// (for the given path and the fully parameterized one)
		// and computeIfAbsent can't do that.
		DereferencerBuilder result = memoizedBuilders.get(path);
		if (result == null) {
			result = getOrCreateBuilder(path);
			DereferencerBuilder previous = memoizedBuilders.putIfAbsent(path, result);
			if (previous != null) {
				// Must not switch to a new DereferencerBuilder, even if it's equivalent.
				// We guarantee that we'll always use the same one.
				return previous;
			}
		}
		return result;
	}

	/**
	 * Note: also memoizes the resulting builder under the fully parameterized path
	 * if different from the given path.
	 */
	private DereferencerBuilder getOrCreateBuilder(Path path) throws InvalidTypeException {
		if (path.isEmpty()) {
			return ROOT_BUILDER;
		} else {
			StepwiseDereferencerBuilder candidate = new StepwiseDereferencerBuilder(path, here());

			// If there's already an equivalent one filed under
			// the fully parameterized path, reuse that instead;
			// else, file our candidate under that path.
			Path fullyParameterizedPath = candidate.fullyParameterizedPath();
			DereferencerBuilder result = memoizedBuilders.computeIfAbsent(fullyParameterizedPath, x -> candidate);
			if (result == candidate) {
				// Keep this dereferencer from being collected, so it can be reused
				keepAliveFullyParameterizedPaths.add(fullyParameterizedPath);
			}
			return result;
		}
	}

	/**
	 * Contains code generation logic representing the actions relating to a single segment within a Path.
	 */
	private interface Step {
		/**
		 * @return the {@link Type} that would be returned by a reference ending with the {@link Path} segment that this Step corresponds to.
		 * This is the type of the object that would be returned by the dereferencer.
		 * For primitives, this is the boxed type.
		 */
		Type targetType();

		/**
		 * @return the segment this step contributes to {@link StepwiseDereferencerBuilder#fullyParameterizedPath()}.
		 * Note that if the returned string is a segment name (of the form "<code>-name-</code>")
		 * then it is not guaranteed to be unique within the path.
		 * @see StepwiseDereferencerBuilder#disambiguateDuplicateParameters
		 */
		String fullyParameterizedPathSegment();

		/**
		 * <dl>
		 *     <dt>Initial stack</dt><dd>penultimateObject</dd>
		 *     <dt>Final stack</dt><dd>targetObject</dd>
		 * </dl>
		 */
		void generate_get();

		/**
		 * <dl>
		 *     <dt>Initial stack</dt><dd>penultimateObject newTargetObject</dd>
		 *     <dt>Final stack</dt><dd>newPenultimateObject</dd>
		 * </dl>
		 */
		void generate_with();

		default Class<?> targetClass() {
			return rawClass(targetType());
		}
	}

	/**
	 * A {@link Step} leading to an object for which {@link BoskDriver#submitDeletion} is allowed.
	 */
	private interface DeletableStep extends Step {
		/**
		 * <dl>
		 *     <dt>Initial stack</dt><dd>penultimateObject</dd>
		 *     <dt>Final stack</dt><dd>newPenultimateObject</dd>
		 * </dl>
		 */
		void generate_without();
	}

	/**
	 * Implements {@link SkeletonDereferencerBuilder} by generating the {@link Dereferencer}
	 * methods from a list of {@link Step} objects (which sort of serve as the "intermediate
	 * representation" for this compiler).
	 */
	private final class StepwiseDereferencerBuilder extends SkeletonDereferencerBuilder {
		final List<Step> steps;

		//
		// Construction
		//

		public StepwiseDereferencerBuilder(Path path, StackWalker.StackFrame sourceFileOrigin) throws InvalidTypeException {
			super("DEREFERENCER", rawClass(sourceType).getClassLoader(), sourceFileOrigin);
			assert !path.isEmpty();
			steps = new ArrayList<>();
			Type currentType = sourceType;
			for (int i = 0; i < path.length(); i++) {
				Step step = newSegmentStep(currentType, path.segment(i), i);
				steps.add(step);
				currentType = step.targetType();
			}
			LOGGER.debug("Steps for {}: {}", path, steps);
		}

		/**
		 * @param currentType the type within which {@code segment} is defined
		 */
		private Step newSegmentStep(Type currentType, String segment, int segmentNum) throws InvalidTypeException {
			Class<?> currentClass = rawClass(currentType);
			if (Catalog.class.isAssignableFrom(currentClass)) {
				return new CatalogEntryStep(parameterType(currentType, Catalog.class, 0), segmentNum);
			} else if (Listing.class.isAssignableFrom(currentClass)) {
				return new ListingEntryStep(parameterType(currentType, Listing.class, 0), segmentNum);
			} else if (SideTable.class.isAssignableFrom(currentClass)) {
				Type keyType = parameterType(currentType, SideTable.class, 0);
				Type targetType = parameterType(currentType, SideTable.class, 1);
				return new SideTableEntryStep(keyType, targetType, segmentNum);
			} else if (TaggedUnion.class.isAssignableFrom(currentClass)) {
				Class<?> caseStaticClass = rawClass(parameterType(currentType, TaggedUnion.class, 0));
				Map<String, Type> typeMap = StateTreeSerializer.getVariantCaseMap(caseStaticClass);
				Type targetType = typeMap.get(segment);
				if (targetType == null) {
					throw new InvalidTypeException("Invalid tag \"" + segment + "\" for TaggedUnion<" + caseStaticClass.getSimpleName() + ">: expected one of " + typeMap.keySet());
				} else {
					return new VariantCaseStep(segment, targetType);
				}
			} else if (StateTreeNode.class.isAssignableFrom(currentClass)) {
				if (isParameterSegment(segment)) {
					throw new InvalidTypeException("Invalid parameter location: expected a field of " + currentClass.getSimpleName());
				}
				Map<String, Method> getters = gettersForConstructorParameters(currentClass);

				// We currently support getters that are not constructor parameters. Useful for
				// "transient" fields that are actually computed dynamically, but they're also
				// a bit complicated and problematic. If we drop support, we should throw
				// InvalidTypeException here instead of adding the getter to the map. -pdoyle
				getters.put(segment, getterMethod(currentClass, segment));

				Step fieldStep = newFieldStep(segment, getters, ReferenceUtils.getCanonicalConstructor(currentClass));
				Class<?> targetClass = rawClass(fieldStep.targetType());
				if (Optional.class.isAssignableFrom(targetClass)) {
					return new OptionalValueStep(parameterType(fieldStep.targetType(), Optional.class, 0), fieldStep);
				} else if (Phantom.class.isAssignableFrom(targetClass)) {
					return new PhantomValueStep(parameterType(fieldStep.targetType(), Phantom.class, 0), segment);
				} else {
					return fieldStep;
				}
			} else {
				throw new InvalidTypeException("Can't reference contents of " + currentClass.getSimpleName());
			}
		}

		@NotNull
		private Step newFieldStep(String segment, Map<String, Method> getters, Constructor<?> constructor) {
			if (USE_FIELD_STEP) {
				return new FieldStep(segment, getters, constructor);
			} else {
				// FieldStep is technically redundant if we have CustomStep,
				// though TBH the former is more understandable.
				Method getter = getters.get(segment);
				Type targetType = getter.getGenericReturnType();
				MethodHandle methodHandle_get;
				MethodHandle methodHandle_with;
				try {
					methodHandle_get = MethodHandles.lookup().unreflect(getter);
					methodHandle_with = methodHandle_with(segment, getters, constructor);
				} catch (IllegalAccessException e) {
					throw new NotYetImplementedException(e);
				}
				return new CustomStep(
					targetType,
					segment,
					new ConstantCallSite(methodHandle_get),
					new ConstantCallSite(methodHandle_with)
				);
			}
		}

		private MethodHandle methodHandle_with(String segment, Map<String, Method> getters, Constructor<?> constructor) throws IllegalAccessException {
			MethodHandle result = MethodHandles.lookup().unreflectConstructor(constructor);
			Parameter[] parameters = constructor.getParameters();
			int[] permutation = new int[parameters.length]; // Note: all but one parameter will be left as zero
			for (int i = 0; i < parameters.length; i++) {
				Parameter p = parameters[i];
				if (segment.equals(p.getName())) {
					// This is the one parameter we just pass through
					permutation[i] = 1;
				} else {
					MethodHandle getter = MethodHandles.lookup().unreflect(getters.get(p.getName()));
					result = collectArguments(result, i, getter);
				}
			}
			MethodType resultType = MethodType.methodType(
				constructor.getDeclaringClass(),
				constructor.getDeclaringClass(),
				getters.get(segment).getReturnType());
			return permuteArguments(result, resultType, permutation);
		}

		//
		// Skeleton "generate" methods
		//

		@Override
		protected void generate_get() {
			pushSourceObject(rawClass(sourceType));
			for (Step step: steps) {
				step.generate_get();
				castTo(step.targetClass());
			}
		}

		@Override
		protected void generate_with() {
			pushSegmentStack();
			pushNewValueObject(lastStep().targetClass());
			lastStep().generate_with();
			generateVineFoldingSequence();
		}

		@Override
		protected void generate_without() {
			if (lastStep() instanceof DeletableStep d) {
				pushSegmentStack();
				d.generate_without();
				generateVineFoldingSequence();
			} else {
				pushSourceObject(rawClass(sourceType));
				pushReference();
				invoke(INVALID_WITHOUT);
			}
		}

		@Override public Type targetType() {
			return lastStep().targetType();
		}

		//
		// Helpers called by the "build" methods
		//

		private Step lastStep() {
			return steps.getLast();
		}

		/**
		 * Push values on the stack for each segment in order, except for the last segment
		 * (because that one usually needs special treatment).
		 *
		 * <p>
		 * Initial stack: (nothing)
		 * Final stack: sourceObject, segment_0, segment_1, ..., segment_n-2
		 */
		private void pushSegmentStack() {
			pushSourceObject(rawClass(sourceType));
			for (Step step: steps.subList(0, steps.size()-1)) {
				dup();
				step.generate_get();
				castTo(step.targetClass());
			}
		}

		/**
		 * Repeatedly call "with" until we work our way back out to a new root object.
		 * This is a bit like a "right fold" of the "with" functions.
		 *
		 * <p>
		 * Initial stack: sourceObject, segment_0, segment_1, ..., segment_n-3, newValue_n-2
		 * Final stack: newSourceObject
		 */
		private void generateVineFoldingSequence() {
			for (int i = steps.size()-2; i >= 0; i--) {
				Step step = steps.get(i);
				castTo(step.targetClass());
				step.generate_with();
			}
		}

		//
		// Fully-parameterized path calculation
		//

		@Override
		public Path fullyParameterizedPath() {
			String[] segments =
				steps.stream()
					.map(Step::fullyParameterizedPathSegment)
					.toArray(String[]::new);
			disambiguateDuplicateParameters(segments);
			return Path.of(segments);
		}

		/**
		 * Ensures that all parameter names are unique in the given array of segments
		 * suffixing them with a number if necessary.
		 */
		private void disambiguateDuplicateParameters(String[] segments) {
			Set<String> parametersAlreadyUsed = new HashSet<>();
			for (int i = 0; i < segments.length; i++) {
				String initialSegment = segments[i];
				if (isParameterSegment(initialSegment)) {
					String segment = initialSegment;

					// As long as the segment is a dup, keep incrementing the suffix
					for (
						int suffix = 2;
						!parametersAlreadyUsed.add(segment);
						suffix++
					) {
						segment = initialSegment.substring(0, initialSegment.length()-1) + "_" + suffix + "-";
					}

					segments[i] = segment;
				}
			}
		}

		/**
		 * @return the conventional name to use for a parameter of the given type
		 */
		private String pathParameterName(Type targetType) {
			String name = rawClass(targetType).getSimpleName();
			return "-"
				+ name.substring(0,1).toLowerCase(ROOT)
				+ name.substring(1)
				+ "-";
		}


		//
		// Step implementations for the possible varieties of objects in the tree
		//

		@Value
		public class FieldStep implements Step {
			String name;
			Map<String, Method> gettersByName;
			Constructor<?> constructor;

			private Method getter() { return gettersByName.get(name); }

			@Override public Type targetType() {
				var valueType = valueType();
				if (valueType instanceof Class<?> c) {
					return boxedClass(c);
				} else {
					return valueType;
				}
			}

			public Type valueType() { return getter().getGenericReturnType(); }

			@Override public String fullyParameterizedPathSegment() { return name; }

			@Override public void generate_get() {
				invoke(getter());
				cb.autoBox(valueType());
			}

			@Override public void generate_with() {
				cb.autoUnbox(valueType());

				// This is too complex to do on the stack. Put what we need in local variables.
				LocalVariable newValue = cb.popToLocal();
				LocalVariable originalObject = cb.popToLocal();

				// Create a blank instance of the class
				cb.instantiate(constructor.getDeclaringClass());

				// Make a copy of the object reference to pass to the constructor;
				// the original will be the result we're returning.
				cb.dup();

				// Push constructor parameters and invoke
				for (Parameter parameter: constructor.getParameters()) {
					if (parameter.getName().equals(name)) {
						// This is the parameter we're substituting
						cb.pushLocal(newValue);
					} else {
						// All other parameter values come from originalObject
						cb.pushLocal(originalObject);
						cb.invoke(gettersByName.get(parameter.getName()));
					}
				}
				cb.invoke(constructor);
			}

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + name + ")";
			}
		}

		@Value
		public class CatalogEntryStep implements DeletableStep {
			Type targetType;
			int segmentNum;

			@Override public String fullyParameterizedPathSegment() { return pathParameterName(targetType); }

			@Override public void generate_get() { pushIdAt(segmentNum); pushReference(); invoke(CATALOG_GET); }
			@Override public void generate_with() { invoke(CATALOG_WITH); }
			@Override public void generate_without() { pushIdAt(segmentNum); invoke(CATALOG_WITHOUT); }

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + segmentNum + ")";
			}
		}

		@Value
		public class ListingEntryStep implements DeletableStep {
			Type entryType;
			int segmentNum;

			/**
			 * A reference to a listing entry points at a {@link ListingEntry},
			 * not the Listing's entry type.
			 */
			@Override public Type targetType() {
				return ListingEntry.class;
			}

			@Override public String fullyParameterizedPathSegment() { return pathParameterName(entryType); }

			@Override public void generate_get() { pushIdAt(segmentNum); pushReference(); invoke(LISTING_GET); }
			@Override public void generate_with() { pushIdAt(segmentNum); swap(); invoke(LISTING_WITH); }
			@Override public void generate_without() { pushIdAt(segmentNum); invoke(LISTING_WITHOUT); }

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + segmentNum + ")";
			}
		}

		@Value
		public class SideTableEntryStep implements DeletableStep {
			Type keyType;
			Type targetType;
			int segmentNum;

			@Override public String fullyParameterizedPathSegment() { return pathParameterName(keyType); }

			@Override public void generate_get() { pushIdAt(segmentNum); pushReference(); invoke(SIDE_TABLE_GET); }
			@Override public void generate_with() { pushIdAt(segmentNum); swap(); invoke(SIDE_TABLE_WITH); }
			@Override public void generate_without() { pushIdAt(segmentNum); invoke(SIDE_TABLE_WITHOUT); }

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + segmentNum + ")";
			}
		}

		@Value
		public class OptionalValueStep implements DeletableStep {
			Type targetType;
			Step fieldStep;

			@Override public String fullyParameterizedPathSegment() { return fieldStep.fullyParameterizedPathSegment(); }

			@Override public void generate_get() { fieldStep.generate_get(); pushReference(); invoke(OPTIONAL_OR_THROW); }
			@Override public void generate_with() { invoke(OPTIONAL_OF); fieldStep.generate_with(); }
			@Override public void generate_without() { invoke(OPTIONAL_EMPTY); fieldStep.generate_with(); }

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + fieldStep + ")";
			}
		}

		@Value
		public class PhantomValueStep implements DeletableStep {
			Type targetType;
			String name;

			@Override public String fullyParameterizedPathSegment() { return name; }

			@Override public void generate_get() { pop(); pushReference(); invoke(THROW_NONEXISTENT_ENTRY); }
			@Override public void generate_with() { pop(); pop(); pushReference(); invoke(THROW_CANNOT_REPLACE_PHANTOM); }
			@Override public void generate_without() { /* No effect */ }

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + name + ")";
			}
		}

		@Value
		public class VariantCaseStep implements Step {
			String name;
			Type targetType;

			@Override
			public Type targetType() {
				return targetType;
			}

			@Override
			public String fullyParameterizedPathSegment() {
				return name;
			}

			@Override
			public void generate_get() {
				// Regardless of which case we select from a {@link works.bosk.TaggedUnion},
				// we always just call {@link TaggedUnion#value()}.
				cb.invoke(TAGGED_UNION_VALUE);

				// On a tag mismatch, report nonexistent
				cb.pushString(name);
				pushReference();
				invoke(TAG_CHECK);
			}

			@Override
			public void generate_with() { pop(); pop(); pushReference(); invoke(THROW_CANNOT_REPLACE_VARIANT_CASE); }

			@Override
			public String toString() {
				return getClass().getSimpleName() + "(" + name + ")";
			}
		}

		@Value
		public class CustomStep implements Step {
			Type valueType;
			String fullyParameterizedPathSegment;
			CallSite callSite_get;
			CallSite callSite_with;

			@Override public Type targetType() {
				if (valueType instanceof Class<?> c) {
					return boxedClass(c);
				} else {
					return valueType;
				}
			}

			@Override public String fullyParameterizedPathSegment() { return fullyParameterizedPathSegment; }

			@Override public void generate_get() {
				cb.invokeDynamic("get", callSite_get);
				cb.autoBox(valueType);
			}

			@Override public void generate_with() {
				cb.autoUnbox(valueType);
				cb.invokeDynamic("with", callSite_with);
			}

			@Override
			public String toString() {
				return getClass().getSimpleName();
			}
		}

	}

	/**
	 * Special-purpose {@link Dereferencer} for the empty path.
	 * This peels off a corner case so {@link StepwiseDereferencerBuilder} doesn't
	 * need to deal with it.
	 */
	private static final class RootDereferencer implements Dereferencer {
		@Override public Object get(Object source, Reference<?> ref) { return source; }
		@Override public Object with(Object source, Reference<?> ref, Object newValue) { return newValue; }
		@Override public Object without(Object source, Reference<?> ref) { return DereferencerRuntime.invalidWithout(source, ref); }
	}

	private final DereferencerBuilder ROOT_BUILDER = new DereferencerBuilder() {
		@Override public Type targetType() { return sourceType; }
		@Override public Path fullyParameterizedPath() { return Path.empty(); }
		@Override public Dereferencer buildInstance() { return new RootDereferencer(); }
	};

	//
	// Reflection performed once during initialization
	//

	static final Method CATALOG_GET, CATALOG_WITH, CATALOG_WITHOUT;
	static final Method LISTING_GET, LISTING_WITH, LISTING_WITHOUT;
	static final Method SIDE_TABLE_GET, SIDE_TABLE_WITH, SIDE_TABLE_WITHOUT;
	static final Method OPTIONAL_OF, OPTIONAL_OR_THROW, OPTIONAL_EMPTY;
	static final Method TAGGED_UNION_VALUE, TAG_CHECK, THROW_CANNOT_REPLACE_VARIANT_CASE;
	static final Method THROW_NONEXISTENT_ENTRY, THROW_CANNOT_REPLACE_PHANTOM;
	static final Method INSTANCEOF_OR_NONEXISTENT, INVALID_WITHOUT;

	static {
		try {
			CATALOG_GET = DereferencerRuntime.class.getDeclaredMethod("catalogEntryOrThrow", Catalog.class, Identifier.class, Reference.class);
			CATALOG_WITH = Catalog.class.getDeclaredMethod("with", Entity.class);
			CATALOG_WITHOUT = Catalog.class.getDeclaredMethod("without", Identifier.class);
			INSTANCEOF_OR_NONEXISTENT = DereferencerRuntime.class.getDeclaredMethod("instanceofOrNonexistent", Object.class, Class.class, Reference.class);
			LISTING_GET = DereferencerRuntime.class.getDeclaredMethod("listingEntryOrThrow", Listing.class, Identifier.class, Reference.class);
			LISTING_WITH = DereferencerRuntime.class.getDeclaredMethod("listingWith", Listing.class, Identifier.class, Object.class);
			LISTING_WITHOUT = Listing.class.getDeclaredMethod("withoutID", Identifier.class);
			SIDE_TABLE_GET = DereferencerRuntime.class.getDeclaredMethod("sideTableEntryOrThrow", SideTable.class, Identifier.class, Reference.class);
			SIDE_TABLE_WITH = SideTable.class.getDeclaredMethod("with", Identifier.class, Object.class);
			SIDE_TABLE_WITHOUT = SideTable.class.getDeclaredMethod("without", Identifier.class);
			OPTIONAL_OF = Optional.class.getDeclaredMethod("ofNullable", Object.class);
			OPTIONAL_OR_THROW = DereferencerRuntime.class.getDeclaredMethod("optionalOrThrow", Optional.class, Reference.class);
			OPTIONAL_EMPTY = Optional.class.getDeclaredMethod("empty");
			TAGGED_UNION_VALUE = TaggedUnion.class.getDeclaredMethod("variant");
			TAG_CHECK = DereferencerRuntime.class.getDeclaredMethod("tagCheck", VariantCase.class, String.class, Reference.class);
			THROW_CANNOT_REPLACE_VARIANT_CASE = DereferencerRuntime.class.getDeclaredMethod("throwCannotReplaceVariantCase", Reference.class);
			THROW_NONEXISTENT_ENTRY = DereferencerRuntime.class.getDeclaredMethod("throwNonexistentEntry", Reference.class);
			THROW_CANNOT_REPLACE_PHANTOM = DereferencerRuntime.class.getDeclaredMethod("throwCannotReplacePhantom", Reference.class);
			INVALID_WITHOUT = DereferencerRuntime.class.getDeclaredMethod("invalidWithout", Object.class, Reference.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static List<Path> pathList() {
		List<Path> result = synchronizedList(new ArrayList<>());
		if (LOGGER.isTraceEnabled()) {
			Runtime.getRuntime().addShutdownHook(new Thread(()->
				LOGGER.trace("keepAliveFullyParameterizedPaths:{}",
					result.stream()
						.map(Path::urlEncoded)
						.sorted()
						.collect(joining("\n\t", "\n\t", "")))));
		}
		return result;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(PathCompiler.class);

	private static final boolean USE_FIELD_STEP = false;
}
