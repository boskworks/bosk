package works.bosk.boson.exceptions;

/**
 * The input text is not valid JSON.
 */
public final class JsonSyntaxException extends JsonFormatException {
	public JsonSyntaxException(String message) {
		super(message);
	}

	public JsonSyntaxException(Throwable cause) {
		super(cause);
	}

	public JsonSyntaxException(String message, Throwable cause) {
		super(message, cause);
	}
}
