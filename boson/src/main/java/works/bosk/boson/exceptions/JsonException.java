package works.bosk.boson.exceptions;

public sealed abstract class JsonException extends RuntimeException permits JsonFormatException, JsonProcessingException {
	protected JsonException(String message) {
		super(message);
	}

	protected JsonException(Throwable cause) {
		super(cause);
	}

	protected JsonException(String message, Throwable cause) {
		super(message, cause);
	}

	@SuppressWarnings("unchecked")
	public static <T extends JsonException> T wrap(T exception, String context) {
		String newMessage = context + ": " + exception.getMessage();
		return switch (exception) {
			case JsonFormatException e -> (T)new JsonFormatException(newMessage, e);
			case JsonProcessingException e -> (T)new JsonFormatException(newMessage, e);
		};
	}
}
