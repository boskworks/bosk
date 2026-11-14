package works.bosk.drivers.mongo.internal;

import works.bosk.drivers.mongo.exceptions.DisconnectedException;

/**
 * A {@link DisconnectedException} indicating a preference for an immediate reconnect
 * instead of waiting first, as the {@link ChangeReceiver} normally would.
 * <p>
 * Throw this only when you're confident that reconnecting won't itself cause the
 * same condition to reoccur immediately, or else you can end up with a rapid
 * reconnect spin loop.
 * <p>
 * This is an internal signal between {@link DisconnectedDriver} and
 * {@link ChangeReceiver}, not part of the public API.
 */
class ImmediateReconnectException extends DisconnectedException {
	public ImmediateReconnectException(Throwable cause) {
		super(cause);
	}
}
