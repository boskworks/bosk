package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterizedRootTest {

	public record GenericNode<T>(T value) implements StateTreeNode { }

	@Test
	void rawGenericRootTypeIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> Bosk.simple("test", new GenericNode<>("hello")));
		assertThrows(IllegalArgumentException.class,
			() -> new Bosk<>("test", GenericNode.class,
				_ -> new GenericNode<String>("hello"),
				BoskConfig.<GenericNode<String>>simple()));
	}
}
