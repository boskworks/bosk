package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.types.DataType.BOOLEAN;

/**
 * Describes how to determine whether an object member should be included in the JSON output.
 */
public sealed interface MemberPresenceCondition {
	static MemberPresenceCondition always() {
		return fixed(true);
	}

	static MemberPresenceCondition never() {
		return fixed(false);
	}

	private static @NotNull Nullary fixed(boolean value) {
		return new Nullary(new TypedHandle(
			MethodHandles.constant(boolean.class, value),
			BOOLEAN,
			List.of()
		));
	}

	static Nullary nullary(TypedHandle isPresent) {
		return new Nullary(isPresent);
	}

	static MemberValue memberValue(TypedHandle isPresent) {
		return new MemberValue(isPresent);
	}

	static EnclosingObject enclosingObject(TypedHandle isPresent) {
		return new EnclosingObject(isPresent);
	}

	/**
	 * @param isPresent takes no parameters
	 */
	record Nullary(TypedHandle isPresent) implements MemberPresenceCondition {
		public Nullary {
			assert isPresent.parameterTypes().isEmpty();
			assert isPresent.returnType().equals(BOOLEAN);
		}
	}

	/**
	 * @param isPresent takes one parameter: the object that represents the member in question
	 */
	record MemberValue(TypedHandle isPresent) implements MemberPresenceCondition {
		public MemberValue {
			assert isPresent.parameterTypes().size() == 1;
			assert isPresent.returnType().equals(BOOLEAN);
		}

		public static MemberValue ifNonNull(KnownType argType) {
			return new MemberValue(
				TypedHandle.ofPredicate(argType, Objects::nonNull)
			);
		}
	}

	/**
	 * @param isPresent takes one parameter: the object that represents the object containing the member in question
	 */
	record EnclosingObject(TypedHandle isPresent) implements MemberPresenceCondition {
		public EnclosingObject {
			assert isPresent.parameterTypes().size() == 1;
			assert isPresent.returnType().equals(BOOLEAN);
		}
	}

}
