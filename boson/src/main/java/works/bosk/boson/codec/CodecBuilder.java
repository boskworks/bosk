package works.bosk.boson.codec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.HashMap;
import java.util.Map;
import works.bosk.boson.codec.compiler.SpecCompiler;
import works.bosk.boson.codec.interpreter.SpecInterpretingGenerator;
import works.bosk.boson.codec.interpreter.SpecInterpretingParser;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.JsonValueSpec;

import static java.util.Objects.requireNonNull;

public class CodecBuilder {
	private final TypeMap typeMap;
	private final SpecCompiler compiler;
	private final Map<Package, Lookup> specialLookups = new HashMap<>();

	private CodecBuilder(TypeMap typeMap) {
		this.typeMap = typeMap;
		this.compiler = new SpecCompiler(typeMap, Map.copyOf(specialLookups));
	}

	/**
	 * @param typeMap is used to resolve any {@link works.bosk.boson.mapping.spec.TypeRefNode}s
	 */
	public static CodecBuilder using(TypeMap typeMap) {
		return new CodecBuilder(typeMap);
	}

	/**
	 * When building codecs, uses the given {@link Lookup} object to find {@link MethodHandle}s
	 * for any class in the same package as the {@link Lookup}'s {@linkplain Lookup#lookupClass() lookup class}.
	 *
	 * @return {@code this}
	 */
	public CodecBuilder using(Lookup lookup) {
		specialLookups.put(requireNonNull(lookup.lookupClass().getPackage()), lookup);
		return this;
	}

	public Codec buildInterpreter() {
		return new Codec() {
			@Override
			public Parser parserFor(JsonValueSpec spec) {
				return new SpecInterpretingParser(spec, typeMap);
			}

			@Override
			public Generator generatorFor(JsonValueSpec spec) {
				return new SpecInterpretingGenerator(spec, typeMap);
			}
		};
	}

	public Codec buildCompiled(JsonValueSpec... extraNodes) {
		return compiler.compile(extraNodes);
	}

	public Codec build(JsonValueSpec... extraNodes) {
		return typeMap.settings().compiled()
			? buildCompiled(extraNodes)
			: buildInterpreter();
	}
}
