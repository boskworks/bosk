package works.bosk.drivers.state;

import works.bosk.StateTreeNode;

public record Primitives(
	int intValue,
	long longValue,
	float floatValue,
	double doubleValue,
	boolean booleanValue,
	char charValue,
	byte byteValue,
	short shortValue
) implements StateTreeNode {
	public static Primitives of(int value) {
		return new Primitives(value, value, value, value, value != 0, (char) value, (byte)value, (short) value);
	}
}
