package works.bosk.boson.exceptions;

/**
 * The JSON input text is invalid.
 * <p>
 * This class is concrete so that it can be thrown in the rare situation where
 * we don't know which subclass to throw.
 * If you know which one to throw, you should throw that instead.
 */
public sealed class JsonFormatException extends JsonException permits
	JsonContentException,
	JsonSyntaxException
{
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
