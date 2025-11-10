package works.bosk.boson.exceptions;

/**
 * An unexpected error has occurred during JSON processing.
 * <p>
 * This does not necessarily indicate a problem with input JSON, but rather that
 * something unexpected has gone wrong. A correctly written parser or generator
 * would not throw this exception.
 */
public final class JsonProcessingException extends JsonException {
	public JsonProcessingException(String message) {
		super(message);
	}

	public JsonProcessingException(Throwable cause) {
		super(cause);
	}

	public JsonProcessingException(String message, Throwable cause) {
		super(message, cause);
	}
}
