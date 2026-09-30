package works.bosk.drivers.mongo.internal;

import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;

/**
 * A {@link DisconnectedException} indicating a preference for an immediate reconnect
 * instead of waiting first, as the {@link ChangeReceiver} normally would.
 * <p>
 * Immediate reconnects can lead to a busy loop, so we must take care to use this
 * only in situations that don't arise during the reconnect itself.
 * Currently, the only {@link Throwable#getCause() cause} we support is {@link RevisionDisruptedException}
 * because that can be a symptom of
 * a {@link MongoDriver#refurbish() refurbish} to a different format;
 * and the reconnect does not throw that exception, so a busy loop won't ensue.
 * <p>
 * This is an internal signal between {@link DisconnectedDriver} and
 * {@link ChangeReceiver}, not part of the public API.
 */
class ImmediateReconnectException extends DisconnectedException {
	public ImmediateReconnectException(RevisionDisruptedException cause) {
		super(cause);
	}
}
