package works.bosk.exceptions;

public class NoReadContextException extends IllegalStateException {
	public NoReadContextException(String s) {
		super(s);
	}

	public NoReadContextException(String message, Throwable cause) {
		super(message, cause);
	}
}
