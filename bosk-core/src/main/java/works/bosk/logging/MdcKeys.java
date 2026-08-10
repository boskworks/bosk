package works.bosk.logging;

import works.bosk.Bosk;

/**
 * Keys to use for SLF4J's Mapped Diagnostic Context.
 * <p>
 * {@link #BOSK_NAME} and {@link #BOSK_INSTANCE_ID} are established for the duration of every
 * driver operation and every hook call-back, and re-established by drivers on their own
 * background threads, so that log output can be attributed to the bosk performing the work.
 * The keys are always set to the values of the bosk doing the logging,
 * regardless of which thread or process performs the operation;
 * MDC is for logging, not for propagating context between threads or hosts.
 * <p>
 * The {@code bosk.MongoDriver.*} keys are specific to {@code bosk-mongo}.
 */
public final class MdcKeys {
	/**
	 * The value of {@link Bosk#name()}.
	 * <p>
	 * Propagated automatically by drivers.
	 */
	public static final String BOSK_NAME = "bosk.name";

	/**
	 * The value of {@link Bosk#instanceID()}.
	 * <p>
	 * Propagated automatically by drivers.
	 */
	public static final String BOSK_INSTANCE_ID = "bosk.instanceID";

	/**
	 * A unique string generated for each MongoDB change event received by a particular bosk.
	 */
	public static final String EVENT = "bosk.MongoDriver.event";

	/**
	 * A unique string generated for each MongoDB {@code ClientSession}.
	 * Technically, not every session is a transaction, but we do use them for
	 * transactions, and this name seemed to convey the intent better than "session".
	 */
	public static final String TRANSACTION = "bosk.MongoDriver.transaction";
}
