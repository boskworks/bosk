package works.bosk.opentelemetry;

import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskHook;
import works.bosk.HookRegistrar;
import works.bosk.Reference;
import works.bosk.RegistrarFactory;

/**
 * {@link HookRegistrar} that propagates
 * OpenTelemetry context from the {@link BoskDiagnosticContext diagnostic context}
 * into hooks.
 * <p>
 * Thread-locals are not automatically propagated into hooks,
 * and so OpenTelemetry context must be explicitly propagated.
 * This registrar retrieves diagnostic attributes placed there by {@link OpenTelemetryDriver}
 * and propagate them into hooks.
 */
public final class OpenTelemetryRegistrar implements HookRegistrar {
	final BoskDiagnosticContext diagnosticContext;
	final HookRegistrar downstream;

	OpenTelemetryRegistrar(BoskDiagnosticContext diagnosticContext, HookRegistrar downstream) {
		this.diagnosticContext = diagnosticContext;
		this.downstream = downstream;
	}

	/**
	 * @return a {@link HookRegistrar} that propagates OpenTelemetry context
	 * from the {@link BoskDiagnosticContext diagnostic context} into hooks.
	 */
	public static RegistrarFactory factory() {
		return (b,d) -> new OpenTelemetryRegistrar(b.diagnosticContext(), d);
	}

	@Override
	public <T> void registerHook(String name, Reference<T> scope, BoskHook<T> hook) {
		downstream.registerHook(name, scope, ref -> {
			try (var _ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
				hook.onChanged(ref);
			}
		});
	}
}
