package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterizedRootTest {

	public record GenericNode<T>(T value) implements StateTreeNode { }

	@Test
	void genericRootTypeFailsClearly() {
		String expected = "Root type " + GenericNode.class.getSimpleName() + " is generic;"
			+ " specify its type arguments, for example Types.parameterizedType("
			+ GenericNode.class.getSimpleName() + ".class, String.class)";

		IllegalArgumentException fromSimple = assertThrows(IllegalArgumentException.class,
			() -> Bosk.simple("test", new GenericNode<>("hello")));
		assertEquals(expected, fromSimple.getMessage());

		IllegalArgumentException fromConstructor = assertThrows(IllegalArgumentException.class,
			() -> new Bosk<>("test", GenericNode.class,
				_ -> new GenericNode<String>("hello"),
				BoskConfig.<GenericNode<String>>simple()));
		assertEquals(expected, fromConstructor.getMessage());
	}
}
