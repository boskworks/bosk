package works.bosk.boson.exceptions;

/**
 * Indicates a problem with either invalid JSON text or valid JSON
 * that does not contain the expected contents.
 */
public final class JsonFormatException extends JsonException {
	public JsonFormatException(String message) {
		super(message);
	}

	public JsonFormatException(Throwable cause) {
		super(cause);
	}

	public JsonFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
