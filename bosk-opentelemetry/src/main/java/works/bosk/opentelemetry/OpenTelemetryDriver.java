package works.bosk.opentelemetry;

import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.StateTreeNode;
import works.bosk.drivers.DiagnosticScopeDriver;

public sealed interface OpenTelemetryDriver extends BoskDriver permits ReceiverDriver {
	/**
	 * @return a {@link DriverFactory} that transmits OpenTelemetry context
	 * across the given {@code subject} driver
	 * via the {@link BoskDiagnosticContext diagnostic context}.
	 *
	 * @see OpenTelemetryRegistrar
	 */
	static <RR extends StateTreeNode> DriverFactory<RR> wrapping(DriverFactory<RR> subject) {
		return DriverStack.of(
			DiagnosticScopeDriver.factory(Utils::diagnosticScopeWithContextFromCurrentSpan),
			subject,
			ReceiverDriver.factory()
		);
	}

}
