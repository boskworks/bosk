package works.bosk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.Types;

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

	@Test
	void initialStateReceivesParameterizedRootType() {
		Type rootType = Types.parameterizedType(GenericNode.class, String.class);
		AtomicReference<Type> receivedRootType = new AtomicReference<>();
		DriverFactory<GenericNode<String>> driverFactory = (_, downstream) -> new ForwardingDriver(downstream) {
			@Override
			public <R extends StateTreeNode> R initialState(Type passedRootType) throws InvalidTypeException, IOException, InterruptedException {
				receivedRootType.set(passedRootType);
				return super.initialState(passedRootType);
			}
		};

		Bosk<GenericNode<String>> bosk = new Bosk<>(
			"test",
			rootType,
			_ -> new GenericNode<>("hello"),
			BoskConfig.<GenericNode<String>>builder().driverFactory(driverFactory).build());

		assertEquals(rootType, receivedRootType.get());
		try (var _ = bosk.readSession()) {
			assertEquals(new GenericNode<>("hello"), bosk.rootReference().value());
		}
	}
}
