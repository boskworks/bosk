package works.bosk.boson.codec;

/**
 * A syntactically significant element of JSON text.
 */
public enum Token {
	END_TEXT,
	NULL,
	FALSE,
	TRUE,
	NUMBER,
	START_OBJECT,
	END_OBJECT,
	START_ARRAY,
	END_ARRAY,

	/**
	 * Can be a member name or a string value.
	 * We don't distinguish at the token level.
	 */
	STRING,

	// These are needed for JSON validation. They are all insignificant
	// and could be treated as whitespace otherwise.
	COMMA,
	COLON,
	WHITESPACE,

	ERROR;

	public static Token startingWith(int codePoint) {
		return switch (codePoint) {
			case -1 -> END_TEXT;
			case 'n' -> NULL;
			case 'f' -> FALSE;
			case 't' -> TRUE;
			case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-' -> NUMBER; // Multi-digit numbers can't technically start with a zero
			case '{' -> START_OBJECT;
			case '}' -> END_OBJECT;
			case '[' -> START_ARRAY;
			case ']' -> END_ARRAY;
			case '"' -> STRING;
			case ',' -> COMMA;
			case ':' -> COLON;
			case 0x20, 0x0A, 0x0D, 0x09 -> WHITESPACE;
			default -> ERROR;
		};
	}

	/**
	 * @return true for tokens that are always represented in JSON with the same sequence of characters
	 */
	public boolean hasFixedRepresentation() {
		return switch (this) {
			case END_TEXT,
				 NULL, FALSE, TRUE,
				 START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY,
				 COMMA, COLON ->
				true;
			default ->
				false;
		};
	}

	public String fixedRepresentation() {
		return switch (this) {
			case END_TEXT -> "";
			case NULL -> "null";
			case FALSE -> "false";
			case TRUE -> "true";
			case START_OBJECT -> "{";
			case END_OBJECT -> "}";
			case START_ARRAY -> "[";
			case END_ARRAY -> "]";
			case COMMA -> ",";
			case COLON -> ":";
			default ->
				throw new IllegalArgumentException("Token has no fixed representation: " + this);
		};
	}

	/**
	 * @return true for tokens needed only for validation, not for parsing JSON values
	 */
	public boolean isInsignificant() {
		return switch (this) {
			case COMMA, COLON, WHITESPACE ->
				true;
			default ->
				false;
		};
	}
}
