package works.bosk.opentelemetry;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.StateTreeNode;
import works.bosk.drivers.ContextScopeDriver;

/**
 * A {@link DriverFactory} that transmits OpenTelemetry context
 * across the given {@code subject} driver
 * via diagnostic attributes in the {@link BoskContext bosk context}.
 */
public sealed interface OpenTelemetryDriver extends BoskDriver permits ReceiverDriver {
	/**
	 * @return a {@link DriverFactory} that transmits OpenTelemetry context
	 * across the given {@code subject} driver
	 * via diagnostic attributes in the {@link BoskContext bosk context}.
	 *
	 * @see OpenTelemetryRegistrar
	 */
	static <RR extends StateTreeNode> DriverFactory<RR> wrapping(DriverFactory<RR> subject) {
		return DriverStack.of(
			ContextScopeDriver.factory(Utils::boskContextScopeWithDiagnosticsFromCurrentSpan),
			subject,
			ReceiverDriver.factory()
		);
	}

}
