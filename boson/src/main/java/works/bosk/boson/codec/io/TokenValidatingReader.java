package works.bosk.boson.codec.io;

import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonFormatException;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static works.bosk.boson.codec.Token.ERROR;

/**
 * Stackable layer that adds lexical and syntactical validation to a given {@link JsonReader}.
 * <p>
 * The {@link JsonReader} interface itself is vague as to how much validation it does,
 * specifying only a bare minimum.
 * This class ensures that the input is free of errors that would cause a
 * {@link JsonSyntaxException}.
 * Without this, a reader typically does only the validation required
 * by the {@link JsonReader} interface.
 *
 */
public record TokenValidatingReader(JsonReader downstream) implements JsonReader {

	/**
	 * Closing this closes {@link #downstream}.
	 */
	@Override
	public void close() {
		downstream.close();
	}

	/**
	 * @throws JsonSyntaxException if the next token is invalid; never returns {@link Token#ERROR}
	 */
	@Override
	public Token peekValueToken() {
		Token result = downstream.peekValueToken();
		if (result == ERROR) {
			throw new JsonSyntaxException("Invalid JSON syntax at offset " + currentOffset());
		}
		return result;
	}

	/**
	 * @throws JsonSyntaxException if the next token is invalid; never returns {@link Token#ERROR}
	 */
	@Override
	public Token peekRawToken() {
		Token result = downstream.peekRawToken();
		if (result == ERROR) {
			throw new JsonSyntaxException("Invalid JSON syntax at offset " + currentOffset());
		}
		return result;
	}

	@Override
	public void consumeFixedToken(Token token) {
		if (!token.equals(peekRawToken())) {
			throw new JsonSyntaxException(
				"Expected token " + token + " but found " + peekRawToken() +
				" at offset " + currentOffset());
		}
		try {
			downstream.validateCharacters(token.fixedRepresentation());
		} catch (JsonFormatException e) {
			throw new JsonSyntaxException(e.getMessage(), e);
		}
	}

	@Override
	public CharSequence consumeNumber() {
		CharSequence result = downstream.consumeNumber();
		if (!isValidJsonNumber(result)) {
			throw new JsonSyntaxException(
				"Invalid JSON number '" + result + "' at offset " + currentOffset());
		}
		return result;
	}

	private boolean isValidJsonNumber(CharSequence seq) {
		if ("0".contentEquals(seq)) {
			return true;
		}
		if (!Util.isNumberLeadingChar(seq.charAt(0))) {
			throw new JsonSyntaxException(
				"Invalid leading character in JSON number: '" + seq + "'"
			);
		}
		if (!Character.isDigit(seq.charAt(seq.length()-1))) {
			throw new JsonSyntaxException(
				"Invalid trailing character in JSON number: '" + seq + "'"
			);
		}
		try {
			Double.parseDouble(seq.toString());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public void startConsumingString() {
		downstream.startConsumingString();
	}

	@Override
	public JsonReader withValidation() {
		// Already validating
		return this;
	}

	@Override
	public int nextStringChar() {
		int result = downstream.nextStringChar();
		if (result < 0 && result != END_OF_STRING) {
			throw new JsonSyntaxException("Error in string at offset " + currentOffset());
		}
		return result;
	}

	@Override
	public void skipStringChars(int n) {
		downstream.skipStringChars(n);
	}

	@Override
	public void skipToEndOfString() {
		downstream.skipToEndOfString();
	}

	@Override
	public void validateCharacters(CharSequence expectedCharacters) {
		downstream.validateCharacters(expectedCharacters);
	}

	@Override
	public String previewString(int requestedLength) {
		return downstream.previewString(requestedLength);
	}

	@Override
	public long currentOffset() {
		return downstream.currentOffset();
	}
}
