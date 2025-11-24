package works.bosk.boson.codec.io;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonContentException;
import works.bosk.boson.exceptions.JsonProcessingException;
import works.bosk.boson.exceptions.JsonSyntaxException;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;

import static java.util.Objects.requireNonNull;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.Token.values;

/**
 * Handy wrapper around {@link JsonReader} that makes common operations
 * a little easier to call.
 */
@SuppressWarnings("unused") // called by generated code
public abstract class SharedParserRuntime {
	protected final JsonReader input;

	public SharedParserRuntime(JsonReader input) {
		this.input = requireNonNull(input);
	}

	protected final boolean parseBoolean() {
		Token token = input.peekValueToken();
		skipToken(token);
		return switch (token) {
			case FALSE -> false;
			case TRUE -> true;
			default -> throw new JsonContentException("Expected boolean, not " + token);
		};
	}

	protected final Number parseBigNumber() {
		logEntry("parseBigNumber");
		var token = input.peekValueToken();
		assert token == NUMBER;
		return new BigDecimal(input.consumeNumber().toString());
	}

	protected final Enum<?> parseEnumByName(MethodHandle valueOfHandle) {
		String name = input.consumeString();
		Enum<?> result;
		try {
			result = (Enum<?>) valueOfHandle.invoke(name);
		} catch (Throwable e) {
			throw new JsonProcessingException("Error decoding enum name", e.getCause());
		}
		if (result == null) {
			throw new JsonContentException("No enum constant " + valueOfHandle.type().returnType().getSimpleName() + "." + name);
		}
		return result;
	}

	protected final Object parsePrimitiveNumber(MethodHandle parseHandle) {
		var token = input.peekValueToken();
		assert token == NUMBER;
		String string = input.consumeNumber().toString();
		try {
			return parseHandle.invoke(string);
		} catch (NumberFormatException e) {
			throw new JsonSyntaxException("Invalid number format: [" + string + "]", e);
		} catch (Throwable e) {
			throw new JsonProcessingException("Error decoding number", e.getCause());
		}
	}

	protected final CharSequence readNumberAsCharSequence() {
		Token token = input.peekValueToken();
		if (token != NUMBER) {
			parseError("Expected number, not " + token);
		}
		return input.consumeNumber();
	}

	protected final int peekTokenOrdinal() {
		Token token = input.peekValueToken();
//		LOGGER.debug("peekTokenOrdinal: {}", token);
		return token.ordinal();
	}

	protected final void expect(Token expectedToken) {
		input.expectFixedToken(expectedToken);
	}

	/**
	 * Consumes the token if it's the expected one, like {@link #expect}.
	 *
	 * @return true if the token was the expected one
	 */
	protected final boolean nextTokenIs(Token expectedToken) {
		Token readToken = input.peekValueToken();
		if (readToken == expectedToken) {
			input.consumeFixedToken(readToken);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Utility method so you can toss code in here and see what its bytecode looks like
	 */
	private void decomp(Map<?,?> map) {
//		skip(-1);
//		map.put(parseEnumByName(null), new BigDecimal("0"));
	}

	protected final String parseString() {
		input.peekValueToken(STRING);
		return input.consumeString();
	}

	protected final void startConsumingString() {
		assert input.peekRawToken() == STRING;
		input.startConsumingString();
	}

	protected final int nextStringChar() {
		return input.nextStringChar();
	}

	protected final void skipStringChars(int n) {
		input.skipStringChars(n);
	}

	protected final void skipToEndOfString(int remainingChars) {
		input.skipToEndOfString(remainingChars);
	}

	protected String previewString() {
		if (false && LOGGER.isDebugEnabled()) {
			return input.previewString(10)
				.replace('\n', ' ')
				.replace('\r', ' ');
		} else {
			return "⁇";
		}
	}

	protected final void skipTokenWithOrdinal(int ord) {
		Token token = values()[ord];
//		LOGGER.debug("skipTokenWithOrdinal: {}", token);
		skipToken(token);
	}

	protected final void skipToken(Token expectedToken) {
		var token = input.peekValueToken();
		if (token != expectedToken) {
			parseError("Expected token " + expectedToken + ", not " + token);
		}
		input.consumeFixedToken(expectedToken);
	}

	protected final String readString(int firstChar) {
		return input.consumeString();
	}

	protected final void parseError(String message) {
		String previewString = previewString();
		throw new JsonContentException(message + " at offset " + input.currentOffset() + ": |" + previewString + "|");
	}

	private static final Map<Class<?>, MethodHandle> PRIMITIVE_PARSE_HANDLES = new ConcurrentHashMap<>();

	public static final Map<Class<?>, String> PRIMITIVE_PARSE_METHOD_NAMES = Map.of(
		byte.class, "parseByte",
		short.class, "parseShort",
		int.class, "parseInt",
		long.class, "parseLong",
		float.class, "parseFloat",
		double.class, "parseDouble");

	public static MethodHandle parseHandle(Class<?> primitiveType) {
		assert primitiveType.isPrimitive();
		return PRIMITIVE_PARSE_HANDLES.computeIfAbsent(primitiveType, t -> {
			var methodName = PRIMITIVE_PARSE_METHOD_NAMES.get(t);
			try {
				var method = PrimitiveNumberNode.PRIMITIVE_NUMBER_CLASSES.get(t).getMethod(methodName, String.class);
				return MethodHandles.lookup().unreflect(method);
			} catch (NoSuchMethodException | IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private static final ConcurrentHashMap<Class<?>, MethodHandle> VALUE_OF_HANDLES = new ConcurrentHashMap<>();

	public static MethodHandle valueOfHandle(Class<?> clazz) {
		return VALUE_OF_HANDLES.computeIfAbsent(clazz, SharedParserRuntime::computeValueOfHandle);
	}

	private static MethodHandle computeValueOfHandle(Class<?> boxedType) {
		try {
			Method valueOf = boxedType.getMethod("valueOf", String.class);
			return MethodHandles.lookup().unreflect(valueOf);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalStateException("Unexpected error looking up valueOf for " + boxedType.getSimpleName(), e);
		}
	}

	protected final void logEntry(String methodName) {
		if (false) {
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("{} @ {}: |{}|", methodName, input.currentOffset(), previewString());
			}
		}
	}
	
	protected final void logEntry(String methodName, Object arg) {
		if (false) {
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("{}({}) @ {}: |{}|", methodName, arg, input.currentOffset(), previewString());
			}
			assert !(arg instanceof JsonValueSpec) : "Why are we passing SpecNodes here instead of in the interpreter?";
		}
	}
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SharedParserRuntime.class);

}
