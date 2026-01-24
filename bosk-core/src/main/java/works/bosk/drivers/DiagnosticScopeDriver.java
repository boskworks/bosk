package works.bosk.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.function.Function;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDiagnosticContext.DiagnosticScope;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Automatically sets a {@link DiagnosticScope} around each driver operation
 * based on a user-supplied function.
 * Allows diagnostic context to be supplied automatically to every operation.
 */
public final class DiagnosticScopeDriver implements BoskDriver {
	final BoskDriver downstream;
	final BoskDiagnosticContext diagnosticContext;
	final Function<BoskDiagnosticContext, DiagnosticScope> scopeSupplier;

	private DiagnosticScopeDriver(BoskDriver downstream, BoskDiagnosticContext diagnosticContext, Function<BoskDiagnosticContext, DiagnosticScope> scopeSupplier) {
		this.downstream = downstream;
		this.diagnosticContext = diagnosticContext;
		this.scopeSupplier = scopeSupplier;
	}

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Function<BoskDiagnosticContext, DiagnosticScope> scopeSupplier) {
		return (b, d) -> new DiagnosticScopeDriver(d, b.diagnosticContext(), scopeSupplier);
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			return downstream.initialRoot(rootType);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitReplacement(target, newValue);
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitConditionalCreation(target, newValue);
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitDeletion(target);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (var _ = scopeSupplier.apply(diagnosticContext)) {
			downstream.flush();
		}
	}
}
