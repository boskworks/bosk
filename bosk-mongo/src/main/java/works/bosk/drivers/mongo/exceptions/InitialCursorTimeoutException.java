package works.bosk.drivers.mongo.exceptions;

public class InitialCursorTimeoutException extends InitialRootFailureException {
	public InitialCursorTimeoutException(String message) {
		super(message);
	}

	public InitialCursorTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialCursorTimeoutException(Throwable cause) {
		super(cause);
	}
}
