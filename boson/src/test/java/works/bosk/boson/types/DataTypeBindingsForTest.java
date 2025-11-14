package works.bosk.boson.types;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataTypeBindingsForTest {

	@Test
	<T, L extends Iterable<T>> void testGenericTypes() {
		var typeL = DataType.of(new TypeReference<L>(){});
		var typeListOfString = DataType.of(new TypeReference<List<String>>(){});
		var actual = typeL.bindingsFor(typeListOfString);
		var expected = Map.of("T", DataType.of(String.class), "L", typeListOfString);
		assertEquals(expected, actual);
	}

}
