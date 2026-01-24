package works.bosk.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Implements all {@link BoskDriver} methods by simply calling the corresponding
 * methods on another driver. Useful for overriding one or two methods while leaving
 * the rest unchanged.
 * <p>
 * Unlike {@link ReplicaSet}, this does not automatically fix up the references to
 * point to the right bosk: the references must already be from the bosk controlled
 * by the downstream driver.
 */
public class ForwardingDriver implements BoskDriver {
	protected final BoskDriver downstream;

	public ForwardingDriver(BoskDriver downstream) {
		this.downstream = downstream;
	}

	public static <RR extends StateTreeNode> DriverFactory<RR> factory() {
		return (_, d) -> new ForwardingDriver(d);
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		downstream.submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		downstream.submitConditionalCreation(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		downstream.submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		downstream.submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws InterruptedException, IOException {
		downstream.flush();
	}

	@Override
	public String toString() {
		return "ForwardingDriver{" +
			"downstream=" + downstream +
			'}';
	}
}
