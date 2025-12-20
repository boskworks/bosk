package works.bosk.drivers.mongo.exceptions;

public class InitialCursorCommandException extends InitialRootFailureException {
	public InitialCursorCommandException(String message) {
		super(message);
	}

	public InitialCursorCommandException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialCursorCommandException(Throwable cause) {
		super(cause);
	}
}
