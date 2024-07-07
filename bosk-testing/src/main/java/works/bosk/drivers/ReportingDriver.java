package works.bosk.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.operations.SubmitConditionalDeletion;
import works.bosk.drivers.operations.SubmitConditionalReplacement;
import works.bosk.drivers.operations.SubmitDeletion;
import works.bosk.drivers.operations.SubmitInitialization;
import works.bosk.drivers.operations.SubmitReplacement;
import works.bosk.drivers.operations.UpdateOperation;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Sends an {@link UpdateOperation} to a given listener whenever one of the update methods is called.
 * <p>
 * <em>Implementation note</em>: this class calls the downstream driver using {@link UpdateOperation#submitTo}
 * so that the ordinary {@link DriverConformanceTest} suite also tests all the {@link UpdateOperation} objects.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ReportingDriver<R extends StateTreeNode> implements BoskDriver<R> {
	final BoskDriver<R> downstream;
	final BoskDiagnosticContext diagnosticContext;
	final Consumer<UpdateOperation> updateListener;
	final Runnable flushListener;

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Consumer<UpdateOperation> listener, Runnable flushListener) {
		return (b,d) -> new ReportingDriver<>(d, b.rootReference().diagnosticContext(), listener, flushListener);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		SubmitReplacement<T> op = new SubmitReplacement<>(target, newValue, diagnosticContext.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		SubmitConditionalReplacement<T> op = new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue, diagnosticContext.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		SubmitInitialization<T> op = new SubmitInitialization<>(target, newValue, diagnosticContext.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		SubmitDeletion<T> op = new SubmitDeletion<>(target, diagnosticContext.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		SubmitConditionalDeletion<T> op = new SubmitConditionalDeletion<>(target, precondition, requiredValue, diagnosticContext.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		downstream.flush();
	}
}
