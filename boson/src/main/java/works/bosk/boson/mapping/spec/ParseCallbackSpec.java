package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandle;
import java.util.List;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.KnownType;

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

		KnownType parsedValueType = child.dataType();
		List<KnownType> expected;
		if (before.returnType() == VOID) {
			expected = List.of(parsedValueType);
		} else {
			expected = List.of(before.returnType(), parsedValueType);
		}
		assert after.parameterTypes().equals(expected):
			"Incorrect type of [after] hook. Expected: [" + expected + "], actual: [" + after.parameterTypes() + "]";
	}

	@Override
	public KnownType dataType() {
		return child.dataType();
	}
}
