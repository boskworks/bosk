package works.bosk.logging;

import org.slf4j.MDC;
import works.bosk.Identifier;

public final class MappedDiagnosticContext {

	public static MDCScope setupMDC(String boskName, Identifier boskID) {
		MDCScope result = new MDCScope();
		MDC.put(MdcKeys.BOSK_NAME, boskName);
		MDC.put(MdcKeys.BOSK_INSTANCE_ID, boskID.toString());
		return result;
	}

	/**
	 * This is like {@link org.slf4j.MDC.MDCCloseable} except instead of
	 * deleting the MDC entry at the end, it restores it to its prior value,
	 * which allows us to nest these.
	 *
	 * <p>
	 * You are going to have the urge to use this in an existing
	 * try block that has catch and finally clauses. Resist that urge.
	 * The catch and finally blocks will run after {@link #close()},
	 * and so they won't benefit from the diagnostic context.
	 * You really want to use this in a try block that has no catch or finally clause.
	 */
	public static final class MDCScope implements AutoCloseable {
		final String oldName = MDC.get(MdcKeys.BOSK_NAME);
		final String oldID = MDC.get(MdcKeys.BOSK_INSTANCE_ID);
		@Override public void close() {
			MDC.put(MdcKeys.BOSK_NAME, oldName);
			MDC.put(MdcKeys.BOSK_INSTANCE_ID, oldID);
		}
	}

}
