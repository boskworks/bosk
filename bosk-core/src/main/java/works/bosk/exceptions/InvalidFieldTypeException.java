package works.bosk.exceptions;

public class InvalidFieldTypeException extends InvalidTypeException {
	private final Class<?> containingClass;
	private final String fieldName;

	public Class<?> containingClass() {
		return this.containingClass;
	}

	public String fieldName() {
		return this.fieldName;
	}

	public InvalidFieldTypeException(Class<?> containingClass, String fieldName, String message) {
		super(fullMessage(containingClass, fieldName, message));
		this.containingClass = containingClass;
		this.fieldName = fieldName;
	}

	public InvalidFieldTypeException(Class<?> containingClass, String fieldName, String message, Throwable cause) {
		super(fullMessage(containingClass, fieldName, message), cause);
		this.containingClass = containingClass;
		this.fieldName = fieldName;
	}

	private static String fullMessage(Class<?> containingClass, String fieldName, String message) {
		return "Invalid field " + containingClass.getSimpleName() + "." + fieldName + ": " + message;
	}
}
