package works.bosk.opentelemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import works.bosk.BoskContext;
import works.bosk.MapValue;

class Utils {
	/**
	 * Prefix for OpenTelemetry-related diagnostic attributes.
	 */
	static final String OTEL_PREFIX = "otel.";

	/**
	 * We use the w3c context propagation format.
	 * Future-proof this putting the attributes under a sub-prefix so we can evolve this later.
	 */
	static final String W3C_SUB_PREFIX = "w3c.";

	/**
	 * On the setter side, we'll be given plain old OTel keys and need to add the prefixes.
	 * We add {@link #W3C_SUB_PREFIX} to them here, and then the {@link #OTEL_PREFIX} prefix
	 * is added by {@link BoskContext#withReplacedPrefix}.
	 */
	static final TextMapSetter<Map<String, String>> W3C_MAP_SETTER = (carrier, key, value) -> {
		if (carrier != null) {
			carrier.put(W3C_SUB_PREFIX + key, value);
		}
	};

	/**
	 * On the getter side, it's simplest to deal with plain old OTel keys.
	 * Any prefixes are stripped off while we're building the carrier map.
	 */
	private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
		@Override
		public Iterable<String> keys(Map<String, String> carrier) {
			return carrier.keySet();
		}

		@Override
		public String get(Map<String, String> carrier, String key) {
			if (carrier == null) {
				return null;
			} else {
				return carrier.get(key);
			}
		}
	};

	static Context otelContextFromDiagnosticAttributes(BoskContext boskContext) {
		Map<String, String> otelAttributes = new HashMap<>();
		boskContext.getAttributes().forEach((k, v) -> {
			if (k.startsWith(OTEL_PREFIX)) {
				if (k.startsWith(OTEL_PREFIX + W3C_SUB_PREFIX)) {
					otelAttributes.put(k.substring(OTEL_PREFIX.length() + W3C_SUB_PREFIX.length()), v);
				} else {
					throw new IllegalStateException("Only w3c context propagation is supported; unexpected OpenTelemetry key from diagnostic context: " + k);
				}
			}
		});
		return W3CTraceContextPropagator.getInstance()
			.extract(Context.current(), otelAttributes, MAP_GETTER);
	}

	/**
	 * Stashes OpenTelemetry context into the
	 * diagnostic attributes in the {@link BoskContext bosk context},
	 * where it can be retrieved by
	 * {@link ReceiverDriver} to propagate the context downstream,
	 * and by {@link OpenTelemetryRegistrar} to propagate the context into hooks.
	 */
	static BoskContext.DiagnosticScope diagnosticScopeWithContextFromCurrentSpan(BoskContext boskContext) {
		return boskContext.withReplacedPrefix(OTEL_PREFIX, w3cContextFromCurrentSpan());
	}

	static MapValue<String> w3cContextFromCurrentSpan() {
		Map<String, String> result = new HashMap<>();
		W3CTraceContextPropagator.getInstance()
			.inject(Context.current(), result, W3C_MAP_SETTER);
		return MapValue.copyOf(result);
	}
}
