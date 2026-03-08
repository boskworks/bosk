package works.bosk.opentelemetry;

import java.io.IOException;
import java.lang.reflect.Type;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Propagates OpenTelemetry context from the diagnostic attributes
 * in the {@link BoskContext bosk context} into the downstream driver.
 */
final class ReceiverDriver implements OpenTelemetryDriver {

	private final BoskContext context;
	private final BoskDriver downstream;

	public static <R extends StateTreeNode> DriverFactory<R> factory() {
		return (b, d) -> new ReceiverDriver(b.context(), d);
	}

	ReceiverDriver(BoskContext diagnosticContext1, BoskDriver downstream) {
		this.context = diagnosticContext1;
		this.downstream = downstream;
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			return downstream.initialRoot(rootType);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			downstream.submitReplacement(target, newValue);
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			downstream.submitConditionalCreation(target, newValue);
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			downstream.submitDeletion(target);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (var _ = Utils.otelContextFromDiagnosticAttributes(context).makeCurrent()) {
			downstream.flush();
		}
	}

}
