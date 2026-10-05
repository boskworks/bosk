package works.bosk.jackson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.StateTreeNode;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonNodeDriverParameterizedRootTest {

	public record GenericNode<T>(T value) implements StateTreeNode { }

	public record Point(int x, int y) implements StateTreeNode { }

	@Test
	void mirrorReflectsParameterizedRoot() {
		Type rootType = Types.parameterizedType(GenericNode.class, Point.class);
		List<JsonNodeDriver> drivers = new ArrayList<>();
		Bosk<GenericNode<Point>> bosk = new Bosk<>(
			"test",
			rootType,
			_ -> new GenericNode<>(new Point(1, 2)),
			BoskConfig.<GenericNode<Point>>builder().driverFactory((boskInfo, downstream) -> {
				JsonNodeDriver driver = (JsonNodeDriver) JsonNodeDriver.<GenericNode<Point>>factory(new JacksonSerializer()).build(boskInfo, downstream);
				drivers.add(driver);
				return driver;
			}).build());
		assertEquals(1, drivers.get(0).contents.get("value").get("x").asInt());

		bosk.driver().submitReplacement(bosk.rootReference(), new GenericNode<>(new Point(3, 4)));
		assertEquals(3, drivers.get(0).contents.get("value").get("x").asInt());
	}
}
