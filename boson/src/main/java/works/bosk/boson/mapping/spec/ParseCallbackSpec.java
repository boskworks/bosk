package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;

import static works.bosk.boson.types.DataType.VOID;

/**
 * Invokes the given handles {@code before} and {@code after} parsing the child node.
 * Has no effect on JSON generation.
 * <p>
 * Called every time. {@code before} and {@code after} may have side effects.
 *
 * @param before {@link TypedHandle} returning either {@code void} or a value that will be passed to {@code after}
 * @param child {@link JsonValueSpec} specifying the JSON value expected between the {@link MethodHandle} calls
 * @param after {@link TypedHandle} arguments are, first, the result of {@code before} (if any), and then the parsed value
 */
public record ParseCallbackSpec(
	TypedHandle before,
	JsonValueSpec child,
	TypedHandle after
) implements JsonValueSpec {
	public ParseCallbackSpec {
		assert before.parameterTypes().isEmpty();
		assert after.returnType() == VOID;

		DataType parsedValueType = child.dataType();
		List<DataType> expected;
		if (before.returnType() == VOID) {
			expected = List.of(parsedValueType);
		} else {
			expected = List.of(before.returnType(), parsedValueType);
		}
		assert after.parameterTypes().equals(expected):
			"Incorrect type of [after] hook. Expected: [" + expected + "], actual: [" + after.parameterTypes() + "]";
	}

	@Override
	public DataType dataType() {
		return child.dataType();
	}

	@Override
	public String briefIdentifier() {
		return "ParseCallback";
	}

	@Override
	public ParseCallbackSpec substitute(Map<String, DataType> actualArguments) {
		return new ParseCallbackSpec(
			before.substitute(actualArguments),
			child.substitute(actualArguments),
			after.substitute(actualArguments)
		);
	}
}
