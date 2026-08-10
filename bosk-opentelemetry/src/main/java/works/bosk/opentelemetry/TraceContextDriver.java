package works.bosk.opentelemetry;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.StateTreeNode;
import works.bosk.drivers.ContextScopeDriver;

/**
 * A {@link DriverFactory} that transmits the current span's trace context
 * across the given {@code subject} driver
 * via diagnostic attributes in the {@link BoskContext bosk context}.
 * <p>
 * The current implementation uses the W3C trace context format;
 * other trace context formats could be supported in future.
 */
public sealed interface TraceContextDriver extends BoskDriver permits TraceContextReceiverDriver {
	/**
	 * @return a {@link DriverFactory} that transmits the current span's trace context
	 * across the given {@code subject} driver
	 * via diagnostic attributes in the {@link BoskContext bosk context}.
	 *
	 * @see TraceContextRegistrar
	 */
	static <RR extends StateTreeNode> DriverFactory<RR> wrapping(DriverFactory<RR> subject) {
		return DriverStack.of(
			ContextScopeDriver.factory(Utils::boskContextScopeWithDiagnosticsFromCurrentSpan),
			subject,
			TraceContextReceiverDriver.factory()
		);
	}

}
