package works.bosk.boson.codec.io;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static works.bosk.boson.codec.Token.COLON;
import static works.bosk.boson.codec.Token.COMMA;
import static works.bosk.boson.codec.Token.END_ARRAY;
import static works.bosk.boson.codec.Token.END_OBJECT;
import static works.bosk.boson.codec.Token.END_TEXT;
import static works.bosk.boson.codec.Token.FALSE;
import static works.bosk.boson.codec.Token.NULL;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.START_ARRAY;
import static works.bosk.boson.codec.Token.START_OBJECT;
import static works.bosk.boson.codec.Token.STRING;
import static works.bosk.boson.codec.Token.TRUE;

/**
 * Stackable layer that ensures the token stream from {@code downstream} is valid JSON.
 * While the {@link TokenValidatingReader} ensures that individual tokens are valid,
 * this class ensures the token sequence can be validly parsed as a JSON value.
 */
public final class SyntaxValidatingReader implements JsonReader {
	private final TokenValidatingReader downstream;
	private final Deque<State> stack = new ArrayDeque<>(); // "Dormant" states besides the current state

	/**
	 * Effectively the top of the stack; kept separately for performance,
	 * because we often want to {@link #transitionTo} a new state,
	 * and doing a pop+push is too slow.
	 */
	private State currentState = State.AT_VALUE;

	private void push(State state) {
		stack.push(currentState);
		currentState = state;
	}

	private void pop() {
		if (stack.isEmpty()) {
			currentState = State.DONE_VALUE;
		} else {
			currentState = stack.pop();
		}
	}

	private void transitionTo(State nextState) {
		currentState = nextState;
	}

	/**
	 * This state machine is designed to avoid unnecessary pushes and pops,
	 * in the sense that something like "[1,2,3]" only requires one push and one pop
	 * to start and end the array.
	 */
	enum State {
		AT_VALUE,
		AT_FIRST_MEMBER,        // Might be absent, for an empty object
		AT_MEMBER,              // Can't be absent: we've seen a comma
		AT_COLON,
		AT_MEMBER_VALUE,
		AT_COMMA_OR_END_OBJECT,
		AT_FIRST_ELEMENT,        // Might be absent, for an empty array
		AT_ELEMENT,              // Can't be absent: we've seen a comma
		AT_COMMA_OR_END_ARRAY,

		/**
		 * Pseudo-state indicating a state should be popped off the stack.
		 * Also used as a kind of "terminator" when we expect to be at the end of input.
		 */
		DONE_VALUE,
		;

		static final EnumMap<State, EnumMap<Token, State>> TRANSITIONS = new EnumMap<>(Map.of(
			AT_VALUE, new EnumMap<>(Map.of(
				START_OBJECT,  AT_FIRST_MEMBER,
				START_ARRAY,   AT_FIRST_ELEMENT,
				STRING,        DONE_VALUE,
				NUMBER,        DONE_VALUE,
				TRUE,          DONE_VALUE,
				FALSE,         DONE_VALUE,
				NULL,          DONE_VALUE
			)),
			AT_MEMBER, new EnumMap<>(Map.of(
				STRING, AT_COLON
			)),
			AT_FIRST_MEMBER, new EnumMap<>(Map.of(
				STRING,      AT_COLON,
				END_OBJECT,  DONE_VALUE
			)),
			AT_COLON, new EnumMap<>(Map.of(
				COLON, AT_MEMBER_VALUE
			)),
			AT_MEMBER_VALUE, new EnumMap<>(Map.of(
				START_OBJECT,  AT_FIRST_MEMBER,
				START_ARRAY,   AT_FIRST_ELEMENT,
				STRING,        AT_COMMA_OR_END_OBJECT,
				NUMBER,        AT_COMMA_OR_END_OBJECT,
				TRUE,          AT_COMMA_OR_END_OBJECT,
				FALSE,         AT_COMMA_OR_END_OBJECT,
				NULL,          AT_COMMA_OR_END_OBJECT
			)),
			AT_COMMA_OR_END_OBJECT, new EnumMap<>(Map.of(
				COMMA,       AT_MEMBER,
				END_OBJECT,  DONE_VALUE
			)),
			AT_ELEMENT, new EnumMap<>(Map.of(
				START_OBJECT, AT_FIRST_MEMBER,
				START_ARRAY,  AT_FIRST_ELEMENT,
				STRING,       AT_COMMA_OR_END_ARRAY,
				NUMBER,       AT_COMMA_OR_END_ARRAY,
				TRUE,         AT_COMMA_OR_END_ARRAY,
				FALSE,        AT_COMMA_OR_END_ARRAY,
				NULL,         AT_COMMA_OR_END_ARRAY
			)),
			AT_FIRST_ELEMENT, new EnumMap<>(Map.of(
				START_OBJECT,  AT_FIRST_MEMBER,
				START_ARRAY,   AT_FIRST_ELEMENT,
				STRING,        AT_COMMA_OR_END_ARRAY,
				NUMBER,        AT_COMMA_OR_END_ARRAY,
				TRUE,          AT_COMMA_OR_END_ARRAY,
				FALSE,         AT_COMMA_OR_END_ARRAY,
				NULL,          AT_COMMA_OR_END_ARRAY,
				END_ARRAY,     DONE_VALUE
			)),
			AT_COMMA_OR_END_ARRAY, new EnumMap<>(Map.of(
				COMMA,      AT_ELEMENT,
				END_ARRAY,  DONE_VALUE
			)),
			DONE_VALUE, new EnumMap<>(Map.of(
				END_TEXT, DONE_VALUE // Infinite sequence of END_TEXT tokens is valid
			))
		));

		Map<Token, State> transitions() {
			return TRANSITIONS.get(this);
		}
	}

	/**
	 * Depending on the next state, perform the appropriate stack manipulation.
	 */
	private void doStateTransition(Token token) {
		Map<Token, State> transitions = currentState.transitions();
		State nextState = transitions.get(token);
		switch (nextState) {
			case AT_FIRST_MEMBER, AT_FIRST_ELEMENT -> {
				// We're "recursing" into an object/array. Time to push a new state.

				// When we're done parsing the object/array, we'll pop at that time,
				// and we'll want the state to be what it should be after reading a value.
				// Any value will do, so we just use TRUE here.
				// We could also do this when we pop, but since we already have
				// the `transitions` map handy, it's slightly faster to do it here.
				transitionTo(transitions.get(TRUE));

				push(nextState);
			}
			case DONE_VALUE -> pop();
			case null -> {
				throw new JsonSyntaxException(
					"Unexpected token " + token + " in state " + currentState +
						" at offset " + currentOffset());
			}
			default -> transitionTo(nextState);
		}
	}

	public SyntaxValidatingReader(TokenValidatingReader downstream) {
		this.downstream = downstream;
	}

	@Override
	public void close() {
		downstream.close();
	}

	@Override
	public Token peekValueToken() {
		while (true) {
			Token result = downstream.peekNonWhitespaceToken();
			if (result.isInsignificant()) {
				downstream.consumeFixedToken(result);
				doStateTransition(result);
			} else {
				return result;
			}
		}
	}

	@Override
	public Token peekNonWhitespaceToken() {
		return downstream.peekNonWhitespaceToken();
	}

	@Override
	public Token peekRawToken() {
		return downstream.peekRawToken();
	}

	@Override
	public void consumeFixedToken(Token token) {
		downstream.consumeFixedToken(token);
		doStateTransition(token);
	}

	@Override
	public CharSequence consumeNumber() {
		CharSequence result = downstream.consumeNumber();
		doStateTransition(NUMBER);
		return result;
	}

	@Override
	public void startConsumingString() {
		downstream.startConsumingString();
	}

	@Override
	public int nextStringChar() {
		int result = downstream.nextStringChar();
		if (result == END_OF_STRING) {
			doStateTransition(STRING);
		}
		return result;
	}

	@Override
	public void skipToEndOfString() {
		downstream.skipToEndOfString();
		doStateTransition(STRING);
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

	@Override
	public JsonReader withValidation() {
		// Already validating
		return this;
	}

}
