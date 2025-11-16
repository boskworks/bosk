package works.bosk.boson.codec;

import works.bosk.boson.codec.compiler.SpecCompiler;
import works.bosk.boson.codec.interpreter.SpecInterpretingGenerator;
import works.bosk.boson.codec.interpreter.SpecInterpretingParser;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.JsonValueSpec;

/**
 * Builds a {@link Codec} according to the user's instructions.
 */
public class CodecBuilder {
	private final TypeMap typeMap;
	private final SpecCompiler compiler;

	private CodecBuilder(TypeMap typeMap) {
		this.typeMap = typeMap;
		this.compiler = new SpecCompiler(typeMap);
	}

	/**
	 * @param typeMap is used to resolve any {@link works.bosk.boson.mapping.spec.TypeRefNode}s
	 */
	public static CodecBuilder using(TypeMap typeMap) {
		return new CodecBuilder(typeMap);
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
