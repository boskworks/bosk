package works.bosk.boson.codec;

import java.io.IOException;

/**
 * Creates Java objects corresponding to JSON text.
 */
public interface Parser {
	Object parse(JsonReader json) throws IOException;
}
