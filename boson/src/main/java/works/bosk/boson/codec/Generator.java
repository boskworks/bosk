package works.bosk.boson.codec;

import java.io.Writer;

/**
 * Emits JSON text corresponding to Java objects.
 */
public interface Generator {
	void generate(Writer out, Object value);
}
