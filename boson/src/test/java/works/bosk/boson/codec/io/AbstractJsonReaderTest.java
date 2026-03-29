package works.bosk.boson.codec.io;

import java.util.function.Function;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.junit.InjectFields;
import works.bosk.junit.Injected;

import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.Token.WHITESPACE;

@InjectFields
public class AbstractJsonReaderTest {
	@Injected
	@SuppressWarnings("unused") // Initialized by injection
	private Function<String, ? extends JsonReader> readerSupplier;

	static Token consumeNonWhitespaceToken(JsonReader reader) {
		Token token = reader.peekNonWhitespaceToken();
		assert token != WHITESPACE;
		if (token.isInsignificant()) {
			reader.consumeSyntax(token);
			return token;
		} else {
			return consumeValueToken(reader);
		}
	}

	static Token consumeValueToken(JsonReader reader) {
		Token token = reader.peekValueToken();
		if (token.hasFixedRepresentation()) {
			reader.consumeSyntax(token);
		} else if (token == STRING) {
			reader.startConsumingString();
			reader.skipToEndOfString();
		} else if (token == NUMBER) {
			reader.consumeNumber();
		}
		return token;
	}

	protected JsonReader readerFor(String json) {
		return readerSupplier.apply(json);
	}
}
