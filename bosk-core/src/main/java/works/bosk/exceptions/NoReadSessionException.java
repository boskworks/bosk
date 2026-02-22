package works.bosk.exceptions;

public class NoReadSessionException extends IllegalStateException {
	public NoReadSessionException(String s) {
		super(s);
	}

	public NoReadSessionException(String message, Throwable cause) {
		super(message, cause);
	}
}
