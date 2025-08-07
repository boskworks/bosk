package works.bosk.drivers.mongo.exceptions;

/**
 * Thrown from {@link works.bosk.BoskDriver} methods
 * if we've lost the ability to update the database.
 */
public class DisconnectedException extends RuntimeException {
	public DisconnectedException(String message) {
		super(message);
	}

	public DisconnectedException(String message, Throwable cause) {
		super(message, cause);
	}

	public DisconnectedException(Throwable cause) {
		super(cause);
	}
}
