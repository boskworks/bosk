package works.bosk.boson.mapping;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import works.bosk.boson.types.ErasedType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.PrimitiveType;
import works.bosk.boson.types.UnknownType;

import static java.lang.invoke.MethodType.methodType;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static works.bosk.boson.mapping.spec.PrimitiveNumberNode.PRIMITIVE_NUMBER_CLASSES;

/**
 * Collects information about {@link DataType}s,
 * and then {@link #build() builds} an optimized {@link TypeMap}.
 */
public class TypeScanner {
	final Map<KnownType, TypeRefNode> refs = new LinkedHashMap<>();
	final TypeMap inProgress;
	final Deque<Bundle> bundles = new ArrayDeque<>();
	final Map<Class<? extends Record>, Map<String, FixedMapMember>> recordComponentOverrides = new HashMap<>();

	public TypeScanner(TypeMap.Settings settings) {
		this.inProgress = new TypeMap(settings);
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

		// TODO: The following happens every time we scan a type, even if we've scanned it before.

		// Automatic boxing/unboxing
		if (node instanceof PrimitiveNumberNode pnn) {
			var boxed = new BoxedPrimitiveSpec(pnn);
			inProgress.put(boxed.dataType(), boxed);
		} else if (node instanceof BoxedPrimitiveSpec(PrimitiveNumberNode child)) {
			inProgress.put(child.dataType(), child);
		} else if (type.equals(DataType.CHAR)) {
			inProgress.put(
				DataType.of(Character.class),
				RepresentAsSpec.as(
					node,
					DataType.known(Character.class),
					Character::charValue,
					Character::valueOf
				)
			);
		} else if (type.equals(DataType.of(Character.class))) {
			inProgress.put(
				DataType.CHAR,
				RepresentAsSpec.as(
					node,
					DataType.CHAR,
					Character::valueOf,
					Character::charValue
				)
			);
		} else if (type.equals(DataType.BOOLEAN)) {
			inProgress.put(
				DataType.of(Boolean.class),
				RepresentAsSpec.as(
					node,
					DataType.known(Boolean.class),
					Boolean::booleanValue,
					Boolean::valueOf
				)
			);
		} else if (type.equals(DataType.of(Boolean.class))) {
			inProgress.put(
				DataType.BOOLEAN,
				RepresentAsSpec.as(
					node,
					DataType.BOOLEAN,
					Boolean::valueOf,
					Boolean::booleanValue
				)
			);
		} else if (type.equals(DataType.of(Boolean.class))) {
			inProgress.put(
				DataType.BOOLEAN,
				RepresentAsSpec.as(
					node,
					DataType.BOOLEAN,
					Boolean::valueOf,
					Boolean::booleanValue
				)
			);
		}

		return this;
	}

	/**
	 * Indicates that the given {@code type} is to be associated with the given {@code spec}.
	 * <p>
	 * <em>Note</em>: this can only specify handling for whole types. If you want to override
	 * handling for one particular record field, or for the keys or values of a particular map,
	 * or even the entries of a particular list, that can't be done here.
	 *
	 * @return this
	 * @throws IllegalArgumentException if {@code type} is already associated with a different {@link JsonValueSpec}.
	 */
	public TypeScanner specify(DataType type, JsonValueSpec spec) {
		var old = inProgress.put(type, spec);
		if (old == null || old == spec) {
			return this;
		} else {
			throw new IllegalArgumentException("Multiple specifications for the same type " + type + ": " + old + " and " + spec);
		}
	}

	/**
	 * When scanning types, uses the given {@link Lookup} object to find {@link MethodHandle}s
	 * for any class in the same package as the {@link Lookup}'s {@linkplain Lookup#lookupClass() lookup class}.
	 *
	 * @return {@code this}
	 */
	public TypeScanner use(Lookup lookup) {
		inProgress.add(lookup);
		return this;
	}

	/**
	 * @param types to scan even if not encountered during normal scanning
	 * @param lookups to use for {@link MethodHandle} operations on types that would otherwise be inaccessible
	 * @param directives are considered in order. The first matching directive is used.
	 */
	public record Bundle(
		List<DataType> types,
		List<Lookup> lookups,
		List<Directive> directives
	) { }

	// TODO: A variant of Directive that takes a JsonValueSpec. Then we know we can use the same JsonValueSpec every time
	// rather than being required to ask this spec function to generate a new one each time
	public record Directive(DataType pattern, Function<KnownType, JsonValueSpec> spec) {}

	/**
	 * Adds a new configuration bundle that takes precedence over previously added bundles.
	 * @return {@code this}
	 */
	public TypeScanner addFirst(Bundle bundle) {
		bundles.addFirst(bundle);
		bundle.lookups().forEach(this::use);
		return this;
	}

	/**
	 * Adds a new configuration bundle that applies only if no directives from existing bundles match.
	 * @return {@code this}
	 */
	public TypeScanner addLast(Bundle bundle) {
		bundles.addLast(bundle);
		bundle.lookups().forEach(this::use);
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
		scanRefs();
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
		if (type instanceof KnownType kt && findDirective(kt) instanceof Directive(var pattern, var specFunction)) {
			LOGGER.debug("Type {} matched directive {}", type, pattern);
			JsonValueSpec spec = specFunction.apply(kt);
			LOGGER.debug("Type {} using {}", type, spec);
			return scrapeRefs(spec);
		}
		return switch (type) {
			case ArrayType t -> scanArray(t);
			case BoundType t -> scrapeRefs(scanClass(t));
			case PrimitiveType t -> scanPrimitive(t);
			case UnknownType _, ErasedType _ ->
				throw new IllegalStateException("Unsupported type: " + type);
		};
	}

	private <T extends SpecNode> T scrapeRefs(T spec) {
		LOGGER.debug("Scraping refs from {}", spec);
		switch (spec) {
			case TypeRefNode n -> {
				LOGGER.debug("Found type reference to {}", n.type());
				if (n.type().rawClass().equals(Map.class)) {
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

	private Directive findDirective(KnownType type) {
		for (var bundle : bundles) {
			for (var directive : bundle.directives()) {
				if (directive.pattern().isAssignableFrom(type)) {
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

	private JsonValueSpec scanArray(ArrayType array) {
		throw new IllegalStateException("Not implemented");
	}

	private JsonValueSpec scanPrimitive(PrimitiveType primitive) {
		var clazz = primitive.rawClass();
		if (PRIMITIVE_NUMBER_CLASSES.containsKey(clazz)) {
			return new PrimitiveNumberNode(clazz);
		}
		if (clazz == boolean.class) {
			return new BooleanNode();
		}
		throw new IllegalStateException("Unsupported primitive type: " + primitive);
	}

	private JsonValueSpec scanClass(BoundType type) {
		LOGGER.debug("scanClass({})", type);
		var clazz = type.rawClass();
		if (clazz.isRecord()) {
			return scanRecord(type);
		}
		if (Number.class.isAssignableFrom(clazz)) {
			if (clazz == BigDecimal.class) {
				return new BigNumberNode(BigDecimal.class);
			}
			Class<?> primitiveClass = PRIMITIVE_NUMBER_CLASSES.entrySet().stream()
				.filter(e -> e.getValue() == clazz)
				.map(Map.Entry::getKey)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Unsupported number class: " + clazz));
			return new BoxedPrimitiveSpec(new PrimitiveNumberNode(primitiveClass));
		}
		if (Collection.class.isAssignableFrom(clazz) && clazz.isAssignableFrom(List.class)) {
			return new ArrayNode(
				refNode(type.parameterType(Collection.class, 0)),
				listAccumulator(type),
				listEmitter(type)
			);
		}
		if (Map.class.isAssignableFrom(clazz) && clazz.isAssignableFrom(Map.class)) {
			var keySpec = refNode(type.parameterType(Map.class, 0));
			var valueSpec = refNode(type.parameterType(Map.class, 1));
			return new UniformMapNode(
				keySpec,
				valueSpec,
				mapAccumulator(type),
				mapEmitter(type)
			);
		}
		if (isStringParsingClass(type)) {
			return scanStringParsingClass(type);
		}
		return new TypeRefNode(type);
	}

	public static ArrayAccumulator listAccumulator(BoundType arrayListType) {
		assert arrayListType.rawClass().isAssignableFrom(ArrayList.class);
		MethodHandle creator, listAdd, finisher;
		try {
			creator = MethodHandles.lookup().unreflectConstructor(ArrayList.class.getConstructor());
			listAdd = MethodHandles.lookup().unreflect(List.class.getMethod("add", Object.class));
			finisher = MethodHandles.lookup().unreflect(Collections.class.getMethod("unmodifiableList", List.class));
		} catch (IllegalAccessException | NoSuchMethodException e) {
			throw new IllegalStateException("Unexpected error doing reflection on List", e);
		}
		var upcastCreator = creator.asType(creator.type().changeReturnType(List.class));
		var integrator = listAdd.asType(listAdd.type().changeReturnType(void.class));
		var upcastFinisher = finisher.asType(finisher.type().changeReturnType(arrayListType.rawClass()));
		var listType = new BoundType(List.class, arrayListType.bindings());
		return new ArrayAccumulator(
			new TypedHandle(upcastCreator, listType, List.of()),
			new TypedHandle(integrator, DataType.VOID, List.of(listType, DataType.OBJECT)),
			new TypedHandle(upcastFinisher, arrayListType, List.of(listType))
		);
	}

	public static ArrayEmitter listEmitter(BoundType listType) {
		assert List.class.isAssignableFrom(listType.rawClass());
		if (!(listType.parameterType(List.class, 0) instanceof KnownType elementType)) {
			throw new IllegalStateException("Can't emit from a list of unknown element type: " + listType);
		}
		MethodHandle iterator, hasNext, next;
		try {
			iterator = MethodHandles.lookup().unreflect(List.class.getMethod("iterator"));
			hasNext = MethodHandles.lookup().unreflect(Iterator.class.getMethod("hasNext"));
			next = MethodHandles.lookup().unreflect(Iterator.class.getMethod("next"));
		} catch (IllegalAccessException | NoSuchMethodException e) {
			throw new IllegalStateException("Unexpected error doing reflection on List", e);
		}
		var downcastNext = next.asType(next.type().changeReturnType(elementType.rawClass()));
		var iteratorType = new BoundType(Iterator.class, listType.bindings());
		return new ArrayEmitter(
			new TypedHandle(iterator, iteratorType, List.of(listType)),
			new TypedHandle(hasNext, DataType.BOOLEAN, List.of(iteratorType)),
			new TypedHandle(downcastNext, elementType, List.of(iteratorType))
		);
	}

	public static ObjectAccumulator mapAccumulator(BoundType linkedHashMapType) {
		assert linkedHashMapType.rawClass().isAssignableFrom(LinkedHashMap.class);
		MethodHandle creator, mapPut, finisher;
		try {
			creator = MethodHandles.lookup().unreflectConstructor(LinkedHashMap.class.getConstructor());
			mapPut = MethodHandles.lookup().unreflect(Map.class.getDeclaredMethod("put", Object.class, Object.class));
			finisher = MethodHandles.identity(Map.class).asType(methodType(linkedHashMapType.rawClass(), Map.class));
		} catch (IllegalAccessException | NoSuchMethodException e) {
			throw new IllegalStateException("Unexpected error doing reflection on List", e);
		}
		var upcastCreator = creator.asType(creator.type().changeReturnType(Map.class));
		var integrator = mapPut.asType(mapPut.type().changeReturnType(void.class));
		var mapType = new BoundType(Map.class, linkedHashMapType.bindings());
		return new ObjectAccumulator(
			new TypedHandle(upcastCreator, mapType, List.of()),
			new TypedHandle(integrator, DataType.VOID, List.of(mapType, DataType.OBJECT, DataType.OBJECT)),
			new TypedHandle(finisher, linkedHashMapType, List.of(mapType))
		);
	}

	public static ObjectEmitter mapEmitter(BoundType mapType) {
		assert Map.class.isAssignableFrom(mapType.rawClass());
		if (!(mapType.parameterType(Map.class, 0) instanceof KnownType keyType) ||
			!(mapType.parameterType(Map.class, 1) instanceof KnownType valueType)) {
			throw new IllegalStateException("Can't emit from a map of unknown key or value type: " + mapType);
		}
		Method getIteratorMethod;
		MethodHandle start, hasNext, next, getKey, getValue;
		try {
			start = MethodHandles.lookup().unreflect(TypeScanner.class.getDeclaredMethod("getIterator", Map.class));
			hasNext = MethodHandles.lookup().unreflect(Iterator.class.getMethod("hasNext"));
			next = MethodHandles.lookup().unreflect(Iterator.class.getMethod("next"));
			getKey = MethodHandles.lookup().unreflect(Map.Entry.class.getMethod("getKey"));
			getValue = MethodHandles.lookup().unreflect(Map.Entry.class.getMethod("getValue"));
		} catch (IllegalAccessException | NoSuchMethodException e) {
			throw new IllegalStateException("Unexpected error doing reflection on List", e);
		}
		var upcastStart = start.asType(start.type().changeParameterType(0, mapType.rawClass()));
		var downcastGetKey = getKey.asType(getKey.type().changeReturnType(keyType.rawClass()));
		var downcastGetValue = getValue.asType(getValue.type().changeReturnType(valueType.rawClass()));
		var downcastNext = next.asType(next.type().changeReturnType(Map.Entry.class));
		var mapEntryType = new BoundType(Map.Entry.class, mapType.bindings());
		var iteratorType = new BoundType(Iterator.class, mapEntryType);
		return new ObjectEmitter(
			new TypedHandle(upcastStart, iteratorType, List.of(mapType)),
			new TypedHandle(hasNext, DataType.BOOLEAN, List.of(iteratorType)),
			new TypedHandle(downcastNext, mapEntryType, List.of(iteratorType)),
			new TypedHandle(downcastGetKey, keyType, List.of(mapEntryType)),
			new TypedHandle(downcastGetValue, valueType, List.of(mapEntryType))
		);
	}

	private static Iterator<?> getIterator(Map<?,?> map) {
		return map.entrySet().iterator();
	}

	private static boolean isStringParsingClass(KnownType type) {
		var clazz = type.rawClass();
		return clazz.isEnum() || clazz == String.class;
	}

	private static ScalarSpec scanStringParsingClass(KnownType type) {
		assert isStringParsingClass(type);
		var clazz = type.rawClass();
		if (clazz.isEnum()) {
			return EnumByNameNode.of(clazz);
		}
		if (clazz == String.class) {
			return new StringNode();
		}
		throw new IllegalStateException("Unsupported type: " + clazz);
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
			recordFinisher(recordClass, collect)
		);
	}

	private TypedHandle recordFinisher(Class<?> recordClass, Map<String, FixedMapMember> componentsByName) {
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
		List<KnownType> memberTypes = componentsByName.values().stream().map(FixedMapMember::dataType).toList();
		return new TypedHandle(
			constructor.asType(methodType(recordClass, memberTypes.stream().map(KnownType::rawClass).toArray(Class<?>[]::new))),
			DataType.known(recordClass),
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

	private static final Logger LOGGER = LoggerFactory.getLogger(TypeScanner.class);
}
