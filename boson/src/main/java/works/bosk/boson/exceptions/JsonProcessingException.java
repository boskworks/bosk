package works.bosk.boson.exceptions;

/**
 * An unexpected error has occurred during JSON processing.
 * <p>
 * This should be thrown from code that runs during the JSON parsing or generation process,
 * not from code (like that of {@link works.bosk.boson.mapping.TypeScanner TypeScanner})
 * that runs before JSON processing begins.
 * When in doubt, if your code is running while JSON is being read or written,
 * this is the right exception to throw when an unexpected problem occurs.
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
