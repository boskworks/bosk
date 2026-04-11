package works.bosk.logging;

import works.bosk.Bosk;

/**
 * Keys to use for SLF4J's Mapped Diagnostic Context.
 * <p>
 * Evolution note: we're going to want to get organized in how we generate MDC.
 * For now, only bosk-mongo uses these.
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
