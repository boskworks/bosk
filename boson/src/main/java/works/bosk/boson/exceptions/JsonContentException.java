package works.bosk.boson.exceptions;

/**
 * The JSON input does not match the expected content structure.
 */
public final class JsonContentException extends JsonFormatException {
	public JsonContentException(String message) {
		super(message);
	}

	public JsonContentException(Throwable cause) {
		super(cause);
	}

	public JsonContentException(String message, Throwable cause) {
		super(message, cause);
	}
}
