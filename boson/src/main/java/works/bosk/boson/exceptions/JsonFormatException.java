package works.bosk.boson.exceptions;

/**
 * The JSON input text is invalid.
 */
public sealed abstract class JsonFormatException extends JsonException permits
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
