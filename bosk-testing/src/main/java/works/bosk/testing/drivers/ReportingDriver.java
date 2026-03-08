package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.FlushOperation;
import works.bosk.testing.drivers.operations.SubmitConditionalDeletion;
import works.bosk.testing.drivers.operations.SubmitConditionalReplacement;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.operations.UpdateOperation;

/**
 * Sends an {@link UpdateOperation} to a given listener whenever one of the update methods is called.
 * The listener is called before the update is sent to the downstream driver
 * so that if any hooks are triggered, and those hooks also submit updates,
 * the updates are reported in the right order.
 * Flushes are reported both before and after they are sent to the downstream driver
 * because flushes are fundamentally synchronous, so it makes sense to take some action
 * after a flush.
 * <p>
 * <em>Implementation note</em>: this class calls the downstream driver using {@link UpdateOperation#submitTo}
 * so that the ordinary {@link DriverConformanceTest} suite also tests all the {@link UpdateOperation} objects.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ReportingDriver implements BoskDriver {
	final BoskDriver downstream;
	final BoskContext context;
	final Consumer<? super UpdateOperation> updateListener;
	final Consumer<? super FlushOperation> preFlushListener;
	final Consumer<? super FlushOperation> postFlushListener;

	/**
	 * Builds a driver that reports all updates and flushes to the given listener before sending them to the downstream driver.
	 */
	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Consumer<? super DriverOperation> listener) {
		return (b,d) -> new ReportingDriver(d, b.context(), listener, listener, _->{});
	}

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Consumer<? super UpdateOperation> updateListener, Consumer<? super FlushOperation> preFlushListener, Consumer<? super FlushOperation> postFlushListener) {
		return (b,d) -> new ReportingDriver(d, b.context(), updateListener, preFlushListener, postFlushListener);
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		SubmitReplacement<T> op = new SubmitReplacement<>(target, newValue, context.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		SubmitConditionalReplacement<T> op = new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue, context.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		ConditionalCreation<T> op = new ConditionalCreation<>(target, newValue, context.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		SubmitDeletion<T> op = new SubmitDeletion<>(target, context.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		SubmitConditionalDeletion<T> op = new SubmitConditionalDeletion<>(target, precondition, requiredValue, context.getAttributes());
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		FlushOperation op = new FlushOperation(context.getAttributes());
		preFlushListener.accept(op);
		op.submitTo(downstream);
		postFlushListener.accept(op);
	}

}
