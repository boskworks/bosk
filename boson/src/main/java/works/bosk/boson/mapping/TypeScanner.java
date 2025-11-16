package works.bosk.boson.mapping;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.exceptions.JsonFormatException;
import works.bosk.boson.mapping.TypeScanner.Directive.IsAssignableFrom;
import works.bosk.boson.mapping.opt.Optimizer;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.BigNumberNode;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.EnumByNameNode;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.ScalarSpec;
import works.bosk.boson.mapping.spec.SpecNode;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.handles.ArrayAccumulator;
import works.bosk.boson.mapping.spec.handles.ArrayEmitter;
import works.bosk.boson.mapping.spec.handles.ObjectAccumulator;
import works.bosk.boson.mapping.spec.handles.ObjectEmitter;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.ArrayType;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;
import works.bosk.boson.types.TypeVariable;

import static java.lang.invoke.MethodType.methodType;
import static java.util.Collections.unmodifiableList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static works.bosk.boson.mapping.TypeScanner.Directive.fixed;
import static works.bosk.boson.mapping.spec.PrimitiveNumberNode.PRIMITIVE_NUMBER_CLASSES;
import static works.bosk.boson.types.DataType.CHAR;
import static works.bosk.boson.types.DataType.STRING;

/**
 * Reflectively walks a {@link DataType} to collect information
 * to {@link #build() build} a suitable optimized {@link TypeMap}.
 * <p>
 * Behaviour is customizable via {@link Directive}s, grouped into {@link Bundle}s,
 * which allow you to use Java's type system to declare which types to customize
 * and how to customize them.
 * For a directive with a given {@link Directive#pattern pattern},
 * when a type {@link DataType#isBindableFrom(DataType) isBindableFrom} the pattern,
 * the directive's {@link Directive#spec spec function} will provide a specification
 * that is further {@link JsonValueSpec#specialize specialized} to handle the actual type.
 * <p>
 * The scan aggressively follows any types referenced in {@link TypeRefNode}s,
 * so the specifications returned from directives can use those freely,
 * rather than needing to recursively specify every referenced type.
 * Furthermore, the {@link TypeMap} produced by the {@link #build() build} method
 * is optimized via the {@link Optimizer}, so there's essentially no
 * cost to using {@link TypeRefNode}s.
 */
public class TypeScanner {
	final Map<DataType, TypeRefNode> refs = new LinkedHashMap<>();
	final TypeMap inProgress;
	final Deque<Bundle> bundles = new ArrayDeque<>();
	final Map<Class<? extends Record>, Map<String, FixedMapMember>> recordComponentOverrides = new HashMap<>();

	public TypeScanner(TypeMap.Settings settings) {
		this.inProgress = new TypeMap(settings);
		addFallbackBundle(builtInBundle());
	}

	private <T> Bundle builtInBundle() {
		List<Directive> directives = new ArrayList<>();

		directives.add(fixed(new StringNode()));
		directives.add(fixed(new BigNumberNode(BigDecimal.class)));

		directives.add(fixed(
			new IsAssignableFrom(DataType.of(ArrayList.class)),
			new ArrayNode(ARRAY_LIST_ACCUMULATOR, ITERABLE_EMITTER)
		));

		directives.add(fixed(
			new IsAssignableFrom(DataType.of(LinkedHashMap.class)),
			new UniformMapNode(LINKED_HASH_MAP_ACCUMULATOR, MAP_EMITTER)
		));

		directives.add(new Directive(
			new TypeVariable("E", Enum.class),
			enumType -> switch (enumType) {
				case BoundType bt -> EnumByNameNode.of(bt.rawClass());
				default ->
					throw new IllegalStateException("Expected enum type but got " + enumType);
			}
		));

		// boolean, boxed and unboxed
		directives.add(fixed(new BooleanNode()));
		directives.add(fixed(
			RepresentAsSpec.as(
				new BooleanNode(),
				DataType.known(Boolean.class),
				Boolean::booleanValue,
				Boolean::valueOf
			)
		));

		// char, boxed and unboxed
		directives.add(fixed(
			new RepresentAsSpec(
				new StringNode(),
				new TypedHandle(CHAR2STRING, STRING, List.of(CHAR)),
				new TypedHandle(STRING2CHAR, CHAR, List.of(STRING))
			)
		));
		directives.add(fixed(
			RepresentAsSpec.as(
				new StringNode(),
				DataType.known(Character.class),
				Object::toString,
				string -> string.charAt(0)
			)
		));

		// Primitive numbers, boxed and unboxed
		for (var p: PRIMITIVE_NUMBER_CLASSES.entrySet()) {
			directives.add(fixed(
				new PrimitiveNumberNode(p.getKey())
			));
			directives.add(fixed(
				new BoxedPrimitiveSpec(new PrimitiveNumberNode(p.getKey()))
			));
		}

		// Arrays
		//
		// Ironically, we can't do much better than to represent arrays as lists,
		// since we don't know their size ahead of time. Emitting them could be a bit more efficient.
		directives.add(new Directive(
			DataType.of(new TypeReference<T[]>(){}),
			arrayType -> switch (arrayType) {
				case ArrayType at -> RepresentAsSpec.of(new RepresentAsSpec.Wrangler<T[], List<T>>() {
					final Object[] archetype = at.zeroLengthInstance();

					@Override
					public List<T> toRepresentation(T[] value) {
						return List.of(value);
					}

					@Override
					@SuppressWarnings("unchecked")
					public T[] fromRepresentation(List<T> representation) {
						return (T[])representation.toArray(archetype);
					}
				});
				default ->
					throw new IllegalStateException("Expected ArrayType but got " + arrayType);
			}
		));

		directives.add(new Directive(
			new TypeVariable("R", Record.class),
			recordType -> switch (recordType) {
				case BoundType bt -> scanRecord(bt);
				default ->
					throw new IllegalStateException("Expected record type but got " + recordType);
			}
		));

		List<DataType> types = directives.stream()
			.map(Directive::pattern)
			.filter(t -> t instanceof BoundType b && b.actualArguments().isEmpty())
			.toList();

		return new Bundle(
			"<built-in bundle>",
			types,
			List.of(MethodHandles.lookup()),
			List.copyOf(directives)
		);
	}

	private static String char2string(char c) {
		return String.valueOf(c);
	}

	private static char string2char(String s) {
		if (s.length() != 1) {
			throw new JsonFormatException("String must have length 1 to convert to char: " + s);
		}
		return s.charAt(0);
	}

	private static final MethodHandle CHAR2STRING;
	private static final MethodHandle STRING2CHAR;

	static {
		try {
			var lookup = MethodHandles.lookup();
			CHAR2STRING = lookup.findStatic(TypeScanner.class, "char2string", methodType(String.class, char.class));
			STRING2CHAR = lookup.findStatic(TypeScanner.class, "string2char", methodType(char.class, String.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/**
	 * Inspects the given {@code type} and deduces the appropriate {@link JsonValueSpec} for it.
	 * Has no effect if the given {@code type} has already been specified,
	 * so this is safe to call just to make sure a given type has been specified.
	 * <p>
	 * Recursively scans types referenced from the given {@code type}.
	 * Generally you'll want to make any calls to {@link #specify} first before calling this,
	 * because you can't override a specification afterward.
	 * (This is no great loss, since recursively scanning a given type only to override
	 * it later would be inefficient anyway.)
	 *
	 * @return this
	 */
	public TypeScanner scan(DataType type) {
		var node = inProgress.computeIfAbsent(type, this::computeSpecNode);
		assert type.equals(node.dataType()):
			"Node must have datatype " + type + " but has " + node.dataType() + ": " + node;
		return this;
	}

	/**
	 * Indicates that types matching the given {@code type}
	 * are to be associated with the given {@code spec}.
	 * <p>
	 * Equivalent to adding a {@link Bundle} with a single type
	 * and a single {@link Directive}, like this:
	 *
	 * <pre>
	 *  addBundle(new Bundle(
	 *      List.of(type),
	 *      List.of(),
	 *      List.of(Directive.fixed(spec))
	 *  ));
	 * </pre>
	 * <em>Note</em>: this can only specify handling for whole types. If you want to override
	 * handling for one particular record field, or for the keys or values of a particular map,
	 * or even the entries of a particular list, that can't be done here.
	 *
	 * @return this
	 */
	public TypeScanner specify(DataType type, JsonValueSpec spec) {
		addBundle(new Bundle(
			"<ad hoc bundle for type: " + type + ">",
			List.of(type),
			List.of(),
			List.of(fixed(spec)
		)));
		return this;
	}

	/**
	 * When scanning types, uses the given {@link Lookup} object to find {@link MethodHandle}s
	 * for any class in the same package as the {@link Lookup}'s {@linkplain Lookup#lookupClass() lookup class}.
	 *
	 * @return {@code this}
	 */
	public TypeScanner useLookup(Lookup lookup) {
		inProgress.add(lookup);
		return this;
	}

	/**
	 * A collection of configuration info:
	 * the primary way to tell a {@link TypeScanner} how to do its reflection-based scanning.
	 *
	 * @param name has no significance other than for troubleshooting
	 * @param types to scan even if not encountered during normal scanning
	 * @param lookups to use for {@link MethodHandle} operations on types that would otherwise be inaccessible
	 * @param directives are considered in order. The first matching directive is used.
	 */
	public record Bundle(
		String name,
		List<DataType> types,
		List<Lookup> lookups,
		List<Directive> directives
	) { }

	/**
	 * A rule describing what {@link JsonValueSpec}
	 * to associate with certain {@link DataType}s.
	 * <p>
	 * The {@link #pattern} describes which types the directive applies to.
	 * It may contain {@link TypeVariable}s (but not wildcards).
	 * When a directive is applied to a particular concrete type,
	 * bindings for any type variables will be derived from the concrete type,
	 * and these bindings will be used to {@link JsonValueSpec#specialize specialize}
	 * the {@link #spec}.
	 * <p>
	 * The {@link #guard} further restricts which types the directive applies to.
	 * This is primarily needed by specs that deserialize using a particular subtype;
	 * for example, a directive whose pattern matches {@code List<E>}
	 * might deserialize using an {@code ArrayList<E>}, and so the directive must
	 * not apply to types that are not {@link DataType#isAssignableFrom assignable from}
	 * {@code ArrayList<E>}.
	 * <p>
	 * The {@link #spec} is a function, rather than a fixed {@link JsonValueSpec},
	 * giving it an opportunity to customize the spec based on the particular type
	 * being handled.
	 * In many cases, though, the built-in specialization mechanism suffices,
	 * so the {@link #spec} function ignores its argument and returns a fixed value.
	 * The {@link #fixed} factory methods are provided to handle this very common case.
	 *
	 * @param pattern describes the types to which the directive applies
	 * @param guard further restricts the types to which the directive applies
	 * @param spec function that produces the {@link JsonValueSpec} for a particular type
	 */
	public record Directive(DataType pattern, Guard guard, Function<DataType, JsonValueSpec> spec) {
		public Directive {
			assert !pattern.hasWildcards():
				"Directive pattern must not have wildcards; use type variables instead: " + pattern;
		}

		public Directive(DataType pattern, Function<DataType, JsonValueSpec> spec) {
			this(pattern, ANY, spec);
		}

		/**
		 * A restriction on the types to which a {@link Directive} applies.
		 */
		public sealed interface Guard {
			boolean allows(DataType type);
		}

		/**
		 * No restriction.
		 */
		public record Any() implements Guard {
			@Override
			public boolean allows(DataType type) {
				return true;
			}
		}

		/**
		 * Allows any type that {@link DataType#isAssignableFrom isAssignableFrom}
		 * the directive's pattern. Useful if the directive's spec returns a value
		 * of type {@code subtype}.
		 */
		public record IsAssignableFrom(DataType subtype) implements Guard {
			@Override
			public boolean allows(DataType type) {
				return type.isAssignableFrom(subtype);
			}
		}

		public static final Guard ANY = new Any();

		/**
		 * @return a {@link Directive} that always returns the given {@code spec}
		 * regardless of the actual type.
		 */
		public static Directive fixed(JsonValueSpec spec) {
			// TODO: We could optimize this knowing that the spec function always returns the same value
			return new Directive(
				spec.dataType(),
				_ -> spec
			);
		}

		public static Directive fixed(Guard guard, JsonValueSpec spec) {
			return new Directive(
				spec.dataType(),
				guard,
				_ -> spec
			);
		}

		public boolean appliesTo(DataType type) {
			return pattern.isBindableFrom(type) && guard().allows(type);
		}
	}

	/**
	 * Adds a new configuration bundle that takes precedence over previously added bundles.
	 * <p>
	 * When in doubt between this and {@link #addFallbackBundle(Bundle)}, pick this.
	 * Generally, when you add a bundle, you want it to do what it's designed to do,
	 * and adding it last risks having some of its directives ignored.
	 *
	 * @return {@code this}
	 * @see #addFallbackBundle(Bundle)
	 */
	public TypeScanner addBundle(Bundle bundle) {
		bundles.addFirst(bundle);
		bundle.lookups().forEach(this::useLookup);
		return this;
	}

	/**
	 * Adds a new configuration bundle that applies only if no directives from existing bundles match.
	 * Allows adding "fallback" bundles that supply default handling for types not covered
	 * by other bundles.
	 * <p>
	 * <strong>TL;DR: it might actually be fine after all.</strong>
	 * TODO: This is likely too simplistic, especially once we have built-in bundles.
	 * We can imagine four use cases for adding bundles:
	 * <ol>
	 *     <li>
	 *         No overlap with other bundles. This is probably the most common.
	 *     </li>
	 *     <li>
	 *         Override some or all types from existing bundles.
	 *     </li>
	 *     <li>
	 *         Supply defaults that override built-in functionality but not user-added bundles.
	 *     </li>
	 *     <li>
	 *         Supply defaults that only apply if no other bundle (including built-in) applies.
	 *     </li>
	 * </ol>
	 *
	 * These options are a matter of how pairs of bundles interact, so it's not necessarily
	 * feasible to require this to be specified in the bundles themselves, since
	 * separately developed bundles wouldn't know of each other.
	 * <p>
	 * I'm picturing that, when we add bundles, we also specify types whose directives
	 * should be overridden by the new bundle, and types whose directives should
	 * be overridden by existing bundles.
	 * This means that we can't implement this by resolving the bundles into a single
	 * linear order, since the user might want A to override B for some types and B to
	 * override A for others. This probably entails a topological sort of directives
	 * rather than a simple list of bundles.
	 * <p>
	 * This could get very complicated if the
	 * types don't exactly match those of the directives, because a particular directive
	 * might be partially overridden. Perhaps we'd need to implement this by
	 * generating new directives in this case. For example, if the existing directive
	 * and the new directive both match {@code CharSequence} but we want to override
	 * it only for {@code String}, we'd have a sequence of three directives:
	 *
	 * <ol>
	 *     <li>
	 *         A synthetic directive for {@code String} using the new bundle's handling,
	 *     </li>
	 *     <li>
	 *         The existing directive matching {@code CharSequence} and finally
	 *     </li>
	 *     <li>
	 *         The new directive matching {@code CharSequence}.
	 *     </li>
	 * </ol>
	 *
	 * A problem with the "no overlap" approach is that the natural interpretation of it,
	 * where two directives overlap if there exists a type that matches both, would mean
	 * that all (non-sealed) interfaces would conflict. This would likely cause more problems
	 * than it solves. We probably need a less aggressive concept of "conflict". Perhaps
	 * the notion would be that new bundles override existing ones, but if they add
	 * directives that "dominate" existing ones (ie. the new type {@link DataType#isBindableFrom isBindableFrom}
	 * the existing one), then it must declare that this is deliberate.
	 * Again, it's not clear that this has value: the surprise occurs when you add
	 * a bundle that does nothing, and that is not a risk if "override existing bundles"
	 * is the contract.
	 *
	 * @return {@code this}
	 * @see #addBundle(Bundle)
	 */
	public TypeScanner addFallbackBundle(Bundle bundle) {
		bundles.addLast(bundle);
		bundle.lookups().forEach(this::useLookup);
		return this;
	}

	public TypeScanner specifyRecordFields(Class<? extends Record> type, Map<String, FixedMapMember> componentsByName) {
		var old = recordComponentOverrides.put(type, Map.copyOf(componentsByName));
		if (old != null) {
			throw new IllegalStateException("Already specified record fields for " + type);
		}
		return this;
	}

	public TypeMap build() {
		if (!settings().shallowScan()) {
			scanRefs();
		}
		scanBundleTypes();
		inProgress.freeze();
		LOGGER.debug("Initial TypeMap:\n{}", inProgress.knownTypes().stream()
			.sorted(comparing(Object::toString))
			.map(t -> "\t" + t + " -> " + inProgress.get(t))
			.collect(joining("\n")));
		if (settings().optimize()) {
			TypeMap optimized = new Optimizer().optimize(inProgress);
			optimized.freeze();
			return optimized;
		} else {
			return inProgress;
		}
	}

	private void scanBundleTypes() {
		bundles.forEach(bundle -> bundle.types().forEach(this::scan));
	}

	/**
	 * During the scans, we may have created some TypeRefNodes.
	 * Proactively scan those now, before optimization begins,
	 * to give the most possible information to the optimizer.
	 */
	private void scanRefs() {
		// The scan itself can generate more type references, thereby mutating refs,
		// so we can't simply iterate over refs.
		while (!refs.isEmpty()) {
			var iter = refs.entrySet().iterator();
			var entry = iter.next();
			iter.remove();
			scan(entry.getKey());
		}
	}

	private JsonValueSpec computeSpecNode(DataType type) {
		if (findDirective(type) instanceof Directive(var pattern, _, var specFunction)) {
			LOGGER.debug("Type {} matched directive {}", type, pattern);
			var spec = specFunction.apply(type);

			// This assertion rules out matching on lower bounds, which is unfortunate,
			// but I can't figure out how to make `specialize` work with wildcards.
			assert !spec.dataType().hasWildcards():
				"Spec produced by directive must not have wildcards: " + spec;

			LOGGER.debug("Directive returned {}", spec);
			var specialized = spec.specialize(pattern.bindingsFor(type));
			if (!specialized.equals(spec)) {
				spec = specialized;
				LOGGER.debug("Specialized: {}", spec);
			}
			assert type.equals(spec.dataType()):
				"Expected directive to produce a spec of type " + type
				+ "; got " + spec.dataType();
			return scrapeRefs(spec);
		}

		// We have a type that has no directive yet.
		// We'll defer it with a TypeRefNode in the hopes that
		// a subsequent bundle resolves it.
		// If anyone relies on the value we return here, they're wrong.
		//
		// Unfortunately, returning
		// this TypeRefNode, while not incorrect on its own, can cause
		// a StackOverflowError if no directives override this.
		// TODO: Handle this case more cleanly
		// TODO: Do we really want to handle this case? Why not require
		// all bundles to be added before scanning starts?
		return new TypeRefNode(type);
	}

	private <T extends SpecNode> T scrapeRefs(T spec) {
		LOGGER.debug("Scraping refs from {}", spec);
		switch (spec) {
			case TypeRefNode n -> {
				LOGGER.debug("Found type reference to {}", n.type());
				if (n.type().leastUpperBoundClass().equals(Map.class)) {
					LOGGER.trace("IT'S A MAP");
				}
				refs.putIfAbsent(n.type(), n);
			}
			case MaybeNullSpec(var inner) -> scrapeRefs(inner);
			case ArrayNode(var elementSpec, _, _) -> scrapeRefs(elementSpec);
			case ParseCallbackSpec(_, var child, _) -> scrapeRefs(child);
			case RepresentAsSpec(var representation, _, _) -> scrapeRefs(representation);
			case FixedMapNode(var members, var _) -> {
				members.values().forEach(m -> scrapeRefs(m.valueSpec()));
			}
			case UniformMapNode(var keySpec, var valueSpec, _, _) -> {
				scrapeRefs(keySpec);
				scrapeRefs(valueSpec);
			}
			case MaybeAbsentSpec(var ifPresent, var ifAbsent, _) -> {
				scrapeRefs(ifPresent);
				scrapeRefs(ifAbsent);
			}
			case ScalarSpec _, ComputedSpec _ -> { }
		}
		return spec;
	}

	private Directive findDirective(DataType type) {
		for (var bundle : bundles) {
			LOGGER.trace("Checking bundle {}", bundle.name());
			for (var directive : bundle.directives()) {
				if (directive.appliesTo(type)) {
					return directive;
				}
			}
		}
		LOGGER.debug("Type {} did not match any directive in {}", type,
			bundles.stream()
				.flatMap(b -> b.directives().stream())
				.map(Directive::pattern)
				.map(Object::toString)
				.distinct()
				.sorted()
				.toList());
		return null;
	}

	private JsonValueSpec refNode(DataType type) {
		if (type instanceof KnownType k) {
			return refNode(k);
		} else {
			throw new IllegalStateException("Can't make a TypeRefNode for unknown type " + type);
		}
	}

	private JsonValueSpec refNode(KnownType type) {
		return refs.computeIfAbsent(type, TypeRefNode::new);
	}

	public static ArrayAccumulator listAccumulator(BoundType arrayListType) {
		return ARRAY_LIST_ACCUMULATOR.substitute(ARRAY_LIST_ACCUMULATOR.resultType().bindingsFor(arrayListType));
	}

	public static ArrayEmitter listEmitter(BoundType iterableType) {
		return ITERABLE_EMITTER.substitute(ITERABLE_EMITTER.dataType().bindingsFor(iterableType));
	}

	public static ObjectAccumulator mapAccumulator(BoundType linkedHashMapType) {
		return LINKED_HASH_MAP_ACCUMULATOR.substitute(LINKED_HASH_MAP_ACCUMULATOR.resultType().bindingsFor(linkedHashMapType));
	}

	public static ObjectEmitter mapEmitter(BoundType mapType) {
		return MAP_EMITTER.substitute(MAP_EMITTER.dataType().bindingsFor(mapType));
	}

	private JsonValueSpec scanRecord(BoundType recordType) {
		var actualTypeArguments = recordType.actualArguments();
		Class<?> recordClass = recordType.rawClass();
		var componentOverrides = recordComponentOverrides.getOrDefault(recordClass, Map.of());
		SequencedMap<String, FixedMapMember> collect = Stream.of(recordClass.getRecordComponents())
			.collect(
				Collectors.toMap(
					RecordComponent::getName,
					c -> scanRecordComponent(c, actualTypeArguments, componentOverrides),
					(_,_) -> { throw new IllegalStateException("Duplicate record component name"); },
					LinkedHashMap::new
				)
			);
		return new FixedMapNode(
			collect,
			recordFinisher(recordType, collect)
		);
	}

	private TypedHandle recordFinisher(KnownType recordType, Map<String, FixedMapMember> componentsByName) {
		Class<?> recordClass = recordType.rawClass();
		assert Record.class.isAssignableFrom(recordClass);
		assert componentsByName.keySet().equals(
			Stream.of(recordClass.getRecordComponents())
				.map(RecordComponent::getName)
				.collect(toSet())
		);
		Class<?>[] ctorParameterTypes = Stream.of(recordClass.getRecordComponents())
			.map(RecordComponent::getType)
			.toArray(Class<?>[]::new);
		MethodHandle constructor;
		try {
			constructor = lookupFor(recordClass).unreflectConstructor(recordClass.getDeclaredConstructor(ctorParameterTypes));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalStateException("Unexpected error accessing record constructor for " + recordClass, e);
		}
		List<DataType> memberTypes = componentsByName.values().stream().map(FixedMapMember::dataType).toList();
		return new TypedHandle(
			constructor.asType(methodType(recordClass, memberTypes.stream().map(DataType::leastUpperBoundClass).toArray(Class<?>[]::new))),
			recordType,
			memberTypes);
	}

	private FixedMapMember scanRecordComponent(RecordComponent c, Map<String, DataType> recordTypeArguments, Map<String, FixedMapMember> overrides) {
		if (overrides.get(c.getName()) instanceof FixedMapMember n) {
			return n;
		}
		MethodHandle mh;
		try {
			mh = lookupFor(c.getDeclaringRecord()).unreflect(c.getAccessor());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Unexpected error accessing record component accessor for " + c, e);
		}
		KnownType returnType = (KnownType) DataType.of(c.getGenericType()).substitute(recordTypeArguments);
		KnownType parameterType = (KnownType) DataType.of(c.getDeclaringRecord()).substitute(recordTypeArguments);
		var accessor = new TypedHandle(
			mh.asType(methodType(returnType.rawClass(), parameterType.rawClass())),
			returnType,
			List.of(parameterType));

		DataType type = DataType.of(c.getGenericType()).substitute(recordTypeArguments);
		JsonValueSpec componentSpec = refNode(type);
		if (c.isAnnotationPresent(Nullable.class)) {
			return new FixedMapMember(new MaybeNullSpec(componentSpec), accessor);
		} else {
			return new FixedMapMember(componentSpec, accessor);
		}
	}

	public TypeMap.Settings settings() {
		return inProgress.settings();
	}

	private Lookup lookupFor(Class<?> c) {
		return inProgress.lookupFor(c);
	}

	private static final ArrayAccumulator ARRAY_LIST_ACCUMULATOR = computeArrayListAccumulator();
	private static final ArrayEmitter ITERABLE_EMITTER = computeIterableEmitter();
	private static final ObjectAccumulator LINKED_HASH_MAP_ACCUMULATOR = computeLinkedHashMapAccumulator();
	private static final ObjectEmitter MAP_EMITTER = computeMapEmitter();

	private static <T, L extends Iterable<T>> ArrayAccumulator computeArrayListAccumulator() {
		return ArrayAccumulator.from(new ArrayAccumulator.Wrangler<ArrayList<T>, T, L>() {
			@Override
			public ArrayList<T> create() {
				return new ArrayList<>();
			}

			@Override
			public ArrayList<T> integrate(ArrayList<T> accumulator, T element) {
				accumulator.add(element);
				return accumulator;
			}

			@Override
			@SuppressWarnings("unchecked")
			public L finish(ArrayList<T> accumulator) {
				return (L)unmodifiableList(accumulator);
			}
		});
	}

	private static <T, L extends Iterable<T>> ArrayEmitter computeIterableEmitter() {
		return ArrayEmitter.from(
			new ArrayEmitter.Wrangler<L, Iterator<T>, T>() {
				@Override
				public Iterator<T> start(L value) {
					return value.iterator();
				}

				@Override
				public boolean hasNext(Iterator<T> iterator) {
					return iterator.hasNext();
				}

				@Override
				public T next(Iterator<T> iterator) {
					return iterator.next();
				}
			}
		);
	}

	private static <K, V, M extends Map<K,V>> ObjectAccumulator computeLinkedHashMapAccumulator() {
		return ObjectAccumulator.from(
			new ObjectAccumulator.Wrangler<M, LinkedHashMap<K,V>, K, V>() {
				@Override
				public LinkedHashMap<K, V> create() {
					return new LinkedHashMap<>();
				}

				@Override
				public LinkedHashMap<K, V> integrate(LinkedHashMap<K, V> accumulator, K key, V value) {
					accumulator.put(key, value);
					return accumulator;
				}

				@Override
				@SuppressWarnings("unchecked")
				public M finish(LinkedHashMap<K, V> accumulator) {
					return (M)accumulator;
				}
			}
		);
	}

	private static <K, V, M extends Map<K,V>> ObjectEmitter computeMapEmitter() {
		return ObjectEmitter.forIterator(new ObjectEmitter.IteratorWrangler<M, Iterator<Map.Entry<K, V>>, Map.Entry<K,V>, K, V>() {
			@Override
			public Iterator<Map.Entry<K, V>> start(M obj) {
				return obj.entrySet().iterator();
			}

			@Override
			public boolean hasNext(Iterator<Map.Entry<K, V>> iter) {
				return iter.hasNext();
			}

			@Override
			public Map.Entry<K, V> next(Iterator<Map.Entry<K, V>> iter) {
				return iter.next();
			}

			@Override
			public K getKey(Map.Entry<K, V> member) {
				return member.getKey();
			}

			@Override
			public V getValue(Map.Entry<K, V> member) {
				return member.getValue();
			}
		});
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(TypeScanner.class);
}
