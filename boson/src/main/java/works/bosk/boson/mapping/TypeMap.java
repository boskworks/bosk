package works.bosk.boson.mapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.types.DataType;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class TypeMap {
	private final Settings settings;
	private final Map<DataType, JsonValueSpec> memo;
	private final AtomicBoolean isFrozen = new AtomicBoolean(false);

	private TypeMap(Settings settings, Map<DataType, JsonValueSpec> memo) {
		this.settings = settings;
		this.memo = memo;
	}

	public TypeMap(Settings settings) {
		this(settings, new LinkedHashMap<>());
	}

	public void freeze() {
		isFrozen.set(true);
	}

	public static TypeMap copyOf(TypeMap other) {
		return new TypeMap(other.settings, new LinkedHashMap<>(other.memo));
	}

	public static TypeMap empty(Settings settings) {
		TypeMap typeMap = new TypeMap(settings, Map.of());
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
		return memo.put(type, requireNonNull(newValue));
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
		public static Settings DEFAULT = new Settings(true, false, true, false, false);

		/**
		 * Makes no effort to recurse into structures,
		 * instead using {@link works.bosk.boson.types.TypeReference} for any types encountered.
		 */
		public static Settings SHALLOW = new Settings(false, false, false, false, true);

		public Settings withFewerSwitches() {
			return new Settings(compiled, iterative, optimize, true, shallowScan);
		}

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

	public String contentDescription() {
		return "{\n" + memo.entrySet().stream()
			.map(e -> "\"" + e.getKey() + "\": " + e.getValue())
			.collect(joining("\t\n"))
			+ "}";
	}
}
