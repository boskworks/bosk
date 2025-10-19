package works.bosk.boson.mapping.spec.handles;

import static works.bosk.boson.types.DataType.BOOLEAN;

/**
 * Describes how to determine whether an object member should be included in the JSON output.
 */
public sealed interface MemberPresenceCondition {
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
