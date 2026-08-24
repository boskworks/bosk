package works.bosk.boson.exceptions;

import works.bosk.boson.codec.io.SyntaxValidatingReader;
import works.bosk.boson.codec.io.TokenValidatingReader;

/**
 * The JSON input is well-formed but does not match the expected content.
 * <p>
 * Validation happens in three tiers:
 * <ul>
 *     <li>The {@link TokenValidatingReader} validates that the input is a
 *     stream of JSON tokens.</li>
 *     <li>The {@link SyntaxValidatingReader} validates that the input is a
 *     valid JSON value, such as balanced brackets and member names that are
 *     strings.</li>
 *     <li>The parser validates that the input is the right kind of JSON
 *     value, and throws this exception when it is not.</li>
 * </ul>
 * Malformed input at either reader tier is rejected as {@link JsonSyntaxException}.
 * Neither reader knows what content the parser expects, so neither throws this
 * exception. For example, a string where a boolean is expected is well-formed
 * JSON, so it reaches the parser and fails here.
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
