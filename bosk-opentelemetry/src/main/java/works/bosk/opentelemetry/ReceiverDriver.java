package works.bosk.opentelemetry;

import java.io.IOException;
import java.lang.reflect.Type;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Propagates OpenTelemetry context from the {@link BoskDiagnosticContext diagnostic context}
 * into the downstream driver.
 */
final class ReceiverDriver implements OpenTelemetryDriver {

	private final BoskDiagnosticContext diagnosticContext;
	private final BoskDriver downstream;

	public static <R extends StateTreeNode> DriverFactory<R> factory() {
		return (b, d) -> new ReceiverDriver(b.diagnosticContext(), d);
	}

	ReceiverDriver(BoskDiagnosticContext diagnosticContext1, BoskDriver downstream) {
		this.diagnosticContext = diagnosticContext1;
		this.downstream = downstream;
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			return downstream.initialRoot(rootType);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			downstream.submitReplacement(target, newValue);
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			downstream.submitConditionalCreation(target, newValue);
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			downstream.submitDeletion(target);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (var __ = Utils.contextFromDiagnosticAttributes(diagnosticContext).makeCurrent()) {
			downstream.flush();
		}
	}

}
