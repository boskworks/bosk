package works.bosk.boson.codec;

import java.io.IOException;

public interface Parser {
	Object parse(JsonReader json) throws IOException;
}
