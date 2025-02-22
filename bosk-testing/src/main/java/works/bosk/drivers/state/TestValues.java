package works.bosk.drivers.state;

import java.time.temporal.ChronoUnit;
import lombok.With;
import lombok.experimental.FieldNameConstants;
import works.bosk.ListValue;
import works.bosk.MapValue;
import works.bosk.StateTreeNode;

import static java.time.temporal.ChronoUnit.FOREVER;

@With
@FieldNameConstants
public record TestValues(
	String string,
	ChronoUnit chronoUnit,
	ListValue<String> list,
	MapValue<String> map
) implements StateTreeNode {
	public static TestValues blank() {
		return new TestValues("", FOREVER, ListValue.empty(), MapValue.empty());
	}
}
