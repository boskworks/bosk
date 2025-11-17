package works.bosk.boson.codec;

import works.bosk.boson.mapping.spec.JsonValueSpec;

/**
 * A factory for JSON parsers and generators.
 * Accessible via {@link CodecBuilder}.
 * <p>
 * Typically, the {@code spec} passed to {@link #parserFor(JsonValueSpec) parserFor}
 * and {@link #generatorFor(JsonValueSpec) generatorFor}
 * should come from the same {@link works.bosk.boson.mapping.TypeMap TypeMap}
 * that was used to build this {@code Codec}
 * for optimal performance,
 * but there are occasional use cases to ask for a parser or generator for
 * a spec that isn't the one that the {@code TypeMap} maps to a given type.
 */
public interface Codec {
	Parser parserFor(JsonValueSpec spec);
	Generator generatorFor(JsonValueSpec spec);
}
