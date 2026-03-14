package works.bosk.drivers.mongo.internal;

import java.util.concurrent.FutureTask;
import works.bosk.drivers.mongo.MongoDriver;

import static java.util.Objects.requireNonNull;

/**
 * Unlike other exceptions we use internally, this one is a {@link RuntimeException}
 * because it's thrown from a {@link FutureTask}, and those can't throw checked exceptions.
 * Callers of {@link FutureTask#get()} implementing {@link MongoDriver#initialState}
 * need to handle this appropriately without any help from the compiler.
 */
class DownstreamInitialStateException extends IllegalStateException {
	public DownstreamInitialStateException(String message, Throwable cause) {
		super(message, requireNonNull(cause));
	}

	public DownstreamInitialStateException(Throwable cause) {
		super(requireNonNull(cause));
	}
}
