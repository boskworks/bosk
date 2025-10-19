package works.bosk.boson.codec;

import java.io.Writer;

public interface Generator {
	void generate(Writer out, Object value);
}
