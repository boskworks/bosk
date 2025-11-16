package works.bosk.boson.codec;

import works.bosk.boson.mapping.spec.JsonValueSpec;

/**
 * A factory for JSON parsers and generators.
 * <p>
 * Accessible via {@link CodecBuilder}.
 */
public interface Codec {
	Parser parserFor(JsonValueSpec spec);
	Generator generatorFor(JsonValueSpec spec);
}
