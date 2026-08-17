package works.bosk.boson.codec.io;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UtilTest {

	@ParameterizedTest
	@ValueSource(strings = {"-128", "-1", "0", "1", "127"})
	void parseByte_acceptsFullRange(String text) {
		assertEquals(Byte.parseByte(text), Util.parseByte(text));
	}

	@ParameterizedTest
	@ValueSource(strings = {"-129", "128"})
	void parseByte_rejectsOutOfRange(String text) {
		assertThrows(NumberFormatException.class, () -> Util.parseByte(text));
	}

	@ParameterizedTest
	@ValueSource(strings = {"-32768", "-1", "0", "1", "32767"})
	void parseShort_acceptsFullRange(String text) {
		assertEquals(Short.parseShort(text), Util.parseShort(text));
	}

	@ParameterizedTest
	@ValueSource(strings = {"-32769", "32768"})
	void parseShort_rejectsOutOfRange(String text) {
		assertThrows(NumberFormatException.class, () -> Util.parseShort(text));
	}

}
