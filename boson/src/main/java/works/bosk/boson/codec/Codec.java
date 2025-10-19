package works.bosk.boson.codec;

import works.bosk.boson.mapping.spec.JsonValueSpec;

public interface Codec {
	Parser parserFor(JsonValueSpec spec);
	Generator generatorFor(JsonValueSpec spec);
}
