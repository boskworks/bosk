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
		validateJsonNumber(result);
		return result;
	}

	private void validateJsonNumber(CharSequence seq) {
		// Regex is insanely slow
		try {
			int i = 0;
			if (seq.charAt(0) == '-') {
				i++;
			}
			switch (seq.charAt(i)) {
				case '0' -> i++;
				case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
					do {
						i++;
						if (i == seq.length()) {
							return;
						}
					} while (Character.isDigit(seq.charAt(i)));
				}
				default -> throw new JsonSyntaxException(
					"Invalid leading character in JSON number: '" + seq + "'"
				);
			}
			if (seq.charAt(i) == '.') {
				i++;
				if (!Character.isDigit(seq.charAt(i))) {
					throw new JsonSyntaxException(
						"Invalid fractional part in JSON number: '" + seq + "'"
					);
				}
				do {
					i++;
					if (i == seq.length()) {
						return;
					}
				} while (Character.isDigit(seq.charAt(i)));
			}
			if ((seq.charAt(i) == 'e' || seq.charAt(i) == 'E')) {
				i++;
				if (seq.charAt(i) == '+' || seq.charAt(i) == '-') {
					i++;
				}
				if (!Character.isDigit(seq.charAt(i))) {
					throw new JsonSyntaxException(
						"Invalid exponent part in JSON number: '" + seq + "'"
					);
				}
				do {
					i++;
					if (i == seq.length()) {
						return;
					}
				} while (Character.isDigit(seq.charAt(i)));
			}
			throw new JsonSyntaxException(
				"Invalid trailing characters in JSON number: '" + seq + "'"
			);
		} catch (IndexOutOfBoundsException e) {
			// This is performance critical for valid numbers,
			// so we don't continually check against seq.length() everywhere.
			// Just let it fall off the end and catch the exception.
			throw new JsonSyntaxException("Unexpected end of number constant", e);
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
