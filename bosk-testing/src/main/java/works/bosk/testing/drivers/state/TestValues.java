package works.bosk.testing.drivers.state;

import java.time.temporal.ChronoUnit;
import works.bosk.ListValue;
import works.bosk.MapValue;
import works.bosk.StateTreeNode;

import static java.time.temporal.ChronoUnit.FOREVER;

public record TestValues(
	String string,
	ChronoUnit chronoUnit,
	ListValue<String> list,
	MapValue<String> map,
	Primitives primitives
) implements StateTreeNode {
	public TestValues withString(String string) {
		return new TestValues(string, chronoUnit, list, map, primitives);
	}

	public TestValues withChronoUnit(ChronoUnit chronoUnit) {
		return new TestValues(string, chronoUnit, list, map, primitives);
	}

	public TestValues withList(ListValue<String> list) {
		return new TestValues(string, chronoUnit, list, map, primitives);
	}

	public TestValues withMap(MapValue<String> map) {
		return new TestValues(string, chronoUnit, list, map, primitives);
	}

	public TestValues withPrimitives(Primitives primitives) {
		return new TestValues(string, chronoUnit, list, map, primitives);
	}

	public static TestValues blank() {
		return new TestValues("", FOREVER, ListValue.empty(), MapValue.empty(), Primitives.of(0));
	}

	public static final class Fields {
		public static final String string = "string";
		public static final String chronoUnit = "chronoUnit";
		public static final String list = "list";
		public static final String map = "map";
		public static final String primitives = "primitives";
	}
}
