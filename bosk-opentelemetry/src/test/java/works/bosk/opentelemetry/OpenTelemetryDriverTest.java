package works.bosk.opentelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.StateTreeNode;
import works.bosk.drivers.BufferingDriver;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenTelemetryDriverTest {

	private static OpenTelemetrySdk openTelemetry;

	public record Root(Integer revision) implements StateTreeNode {}

	@BeforeAll
	static void setup() {
		openTelemetry = OpenTelemetrySdk.builder()
			.setTracerProvider(SdkTracerProvider.builder().build())
			.setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
			.buildAndRegisterGlobal();
	}

	@AfterAll
	static void teardown() {
		openTelemetry.close();
	}

	@Test
	void wrapping_propagatesTraceId() throws InterruptedException, IOException {
		DriverFactory<Root> driverFactory = DriverStack.of(
			OpenTelemetryDriver.wrapping(
				// Use a driver that does not call its downstream driver synchronously on the same thread
				// so that the OpenTelemetry thread context is not propagated implicitly.
				// Otherwise, this isn't much of a test.
				BufferingDriver.factory()
			)
		);
		var bosk = new Bosk<>(
			"test-bosk",
			Root.class,
			_ -> new Root(0),
			BoskConfig.<Root>builder()
				.driverFactory(driverFactory)
				.registrarFactory(OpenTelemetryRegistrar.factory())
				.build());
		record Observation(int revision, String traceID) { }
		BlockingQueue<Observation> observations = new LinkedBlockingQueue<>();
		bosk.hookRegistrar().registerHook("attribute observer", bosk.rootReference(), ref -> {
			observations.add(new Observation(ref.value().revision(),
				Span.current().getSpanContext().getTraceId()));
		});

		// There's an initial observation from the time the hook was registered.
		// At that time, there was no active trace
		assertEquals(new Observation(0, TraceId.getInvalid()), observations.take());

		// Submit a change with an active OpenTelemetry span and observe the attributes
		Observation expected;
		var span = openTelemetry.getTracer("test-scope")
			.spanBuilder("test-span")
			.setParent(Context.current())
			.startSpan();
		try (var _ = span.makeCurrent()) {
			bosk.driver().submitReplacement(bosk.rootReference(), new Root(1));
			expected = new Observation(1, Span.current().getSpanContext().getTraceId());
		} finally {
			span.end();
		}

		bosk.driver().flush();
		// Check that the trace context was propagated all the way into the hook
		assertEquals(expected, observations.take());
		assert observations.isEmpty();
	}

}
