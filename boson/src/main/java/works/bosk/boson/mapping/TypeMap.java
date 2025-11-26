package works.bosk.boson.mapping;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.types.DataType;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * The ultimate product of a {@link TypeScanner}.
 * Gives a mapping from {@link DataType} to {@link JsonValueSpec}.
 * Once {@link #freeze frozen}, can be considered immutable.
 * <p>
 * Also tells the {@link Lookup} to use for accessing members of a given class,
 * since users of this often need to do reflection,
 * and there's no other obvious home for the lookups.
 */
public class TypeMap {
	private final Settings settings;
	private final Map<DataType, JsonValueSpec> memo;
	private final Map<Package, Lookup> lookups;
	private final AtomicBoolean isFrozen = new AtomicBoolean(false);

	private TypeMap(Settings settings, Map<DataType, JsonValueSpec> memo, Map<Package, Lookup> lookups) {
		this.settings = settings;
		this.lookups = lookups;
		this.memo = memo;
	}

	public TypeMap(Settings settings) {
		this(settings, new LinkedHashMap<>(), new HashMap<>());
	}

	public void freeze() {
		isFrozen.set(true);
	}

	public boolean isFrozen() {
		return isFrozen.get();
	}

	public static TypeMap copyOf(TypeMap other) {
		return new TypeMap(other.settings, new LinkedHashMap<>(other.memo), new HashMap<>(other.lookups));
	}

	public static TypeMap empty(Settings settings) {
		TypeMap typeMap = new TypeMap(settings, Map.of(), Map.of());
		typeMap.freeze();
		return typeMap;
	}

	public Set<DataType> knownTypes() {
		return Set.copyOf(memo.keySet());
	}

	public Set<JsonValueSpec> knownSpecs() {
		return Set.copyOf(memo.values());
	}

	/**
	 * @throws IllegalArgumentException if there's no node for the given type
	 */
	public JsonValueSpec get(DataType type) {
		var result = memo.get(type);
		if (result == null) {
			throw new IllegalArgumentException("No spec for type " + type);
		}
		return result;
	}

	public JsonValueSpec computeIfAbsent(DataType type, Function<DataType, JsonValueSpec> func) {
		if (isFrozen.get()) {
			throw new IllegalStateException("TypeMap is frozen");
		}
		return requireNonNull(memo.computeIfAbsent(type, func));
	}

	public JsonValueSpec put(DataType type, JsonValueSpec newValue) {
		if (isFrozen.get()) {
			throw new IllegalStateException("TypeMap is frozen");
		}
		if (newValue instanceof TypeRefNode(DataType referencedType) && referencedType.equals(type)) {
			throw new IllegalArgumentException("Attempting to map a type " + type + " to a self-referential TypeRefNode. Do you have a TypeScanner directive that is not matching as it should?");
		}
		return memo.put(type, requireNonNull(newValue));
	}

	/**
	 * @return true if {@code lookup} wasn't already registered
	 */
	public boolean add(Lookup lookup) {
		var old = lookups.put(requireNonNull(lookup.lookupClass().getPackage()), lookup);
		return old != lookup;
	}

	public void forEach(BiConsumer<DataType, JsonValueSpec> action) {
		memo.forEach(action);
	}

	/**
	 * @param shallowScan this is different from the others. They represent experimental options,
	 *                    but this is an important type scanner mode used to avoid unwanted
	 *                    premature scanning of types we're not ready to scan yet.
	 */
	public record Settings(
		boolean compiled,
		boolean iterative,
		boolean optimize,
		boolean fewerSwitches,
		boolean shallowScan
	) {
		public static Settings DEFAULT = new Settings(true, false, true, true, false);

		/**
		 * Makes no effort to recurse into structures,
		 * instead using {@link works.bosk.boson.types.TypeReference} for any types encountered.
		 */
		public static Settings SHALLOW = new Settings(false, false, false, false, true);

		public Settings withCompiled(boolean compiled) {
			return new Settings(compiled, iterative, optimize, fewerSwitches, shallowScan);
		}
	}

	/**
	 * We hold settings here not because it makes sense, but because it's a convenient
	 * thing that everyone can access.
	 */
	public Settings settings() {
		return settings;
	}

	/**
	 * @return a {@link Lookup} suitable for accessing members of the given class;
	 * if none has been configured, returns {@link MethodHandles#publicLookup()}
	 */
	public Lookup lookupFor(Class<?> c) {
		return lookups.getOrDefault(c.getPackage(), MethodHandles.publicLookup());
	}

	public String contentDescription() {
		return "{\n" + memo.entrySet().stream()
			.map(e -> "\"" + e.getKey() + "\": " + e.getValue())
			.collect(joining("\t\n"))
			+ "}";
	}
}
