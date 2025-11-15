package works.bosk.boson.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.codec.io.Util.fast_isInsignificant;

class SharedParserRuntimeTest {

	@Test
	void testIsInsignificant() {
		int[] insignificant = new int[]{0x20, 0x0A, 0x0D, 0x09, ',', ':'};
		for (int i: insignificant) {
			for (int delta: new int[]{1, -1, 64, -64}) {
				assertEquals(isInsignificant(i+delta), fast_isInsignificant(i+delta), i + "+" + delta);
			}
		}
	}

	private boolean isInsignificant(int codePoint) {
		return Token.startingWith(codePoint) == Token.INSIGNIFICANT;
	}

}
