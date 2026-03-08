package works.bosk.opentelemetry;

import works.bosk.BoskContext;
import works.bosk.BoskHook;
import works.bosk.HookRegistrar;
import works.bosk.Reference;
import works.bosk.RegistrarFactory;

/**
 * {@link HookRegistrar} that propagates OpenTelemetry context from
 * the diagnostic attributes in the {@link BoskContext bosk context}
 * into hooks.
 * <p>
 * Thread-locals are not automatically propagated into hooks,
 * and so OpenTelemetry context must be explicitly propagated.
 * This registrar retrieves diagnostic attributes placed there by {@link OpenTelemetryDriver}
 * and propagate them into hooks.
 */
public final class OpenTelemetryRegistrar implements HookRegistrar {
	final BoskContext context;
	final HookRegistrar downstream;

	OpenTelemetryRegistrar(BoskContext context, HookRegistrar downstream) {
		this.context = context;
		this.downstream = downstream;
	}

	/**
	 * @return a {@link HookRegistrar} that propagates OpenTelemetry context
	 * from the diagnostic attributes in the {@link BoskContext bosk context} into hooks.
	 */
	public static RegistrarFactory factory() {
		return (b,d) -> new OpenTelemetryRegistrar(b.context(), d);
	}

	@Override
	public <T> void registerHook(String name, Reference<T> scope, BoskHook<T> hook) {
		downstream.registerHook(name, scope, ref -> {
			try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
				hook.onChanged(ref);
			}
		});
	}
}
