package works.bosk.testing.drivers;

import java.io.IOException;
import org.slf4j.MDC;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

import static works.bosk.logging.MdcKeys.BOSK_INSTANCE_ID;
import static works.bosk.logging.MdcKeys.BOSK_NAME;

/**
 * Ensures that the {@code bosk.name} and {@code bosk.instanceID} MDC keys are set
 * to the values of the bosk performing the work during every driver operation.
 */
public final class MdcCheckingDriver implements BoskDriver {
	private final String boskName;
	private final String boskInstanceID;
	private final BoskDriver downstream;

	private MdcCheckingDriver(String boskName, String boskInstanceID, BoskDriver downstream) {
		this.boskName = boskName;
		this.boskInstanceID = boskInstanceID;
		this.downstream = downstream;
	}

	/**
	 * @return a driver factory that wraps {@code subject} with an {@link MdcCheckingDriver}
	 * on its downstream side.
	 */
	public static <RR extends StateTreeNode> DriverFactory<RR> wrap(DriverFactory<RR> subject) {
		return DriverStack.of(subject, factory());
	}

	private static <RR extends StateTreeNode> DriverFactory<RR> factory() {
		return (b, d) -> new MdcCheckingDriver(b.name(), b.instanceID().toString(), d);
	}

	@Override
	public <R extends StateTreeNode> R initialState(Class<R> rootType) throws InvalidTypeException, IOException, InterruptedException {
		checkMDC();
		return downstream.initialState(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		checkMDC();
		downstream.submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		checkMDC();
		downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		checkMDC();
		downstream.submitConditionalCreation(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		checkMDC();
		downstream.submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		checkMDC();
		downstream.submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		checkMDC();
		downstream.flush();
	}

	private void checkMDC() {
		if (!boskName.equals(MDC.get(BOSK_NAME))) {
			throw new AssertionError("MDC bosk name must be " + boskName + " but was " + MDC.get(BOSK_NAME));
		}
		if (!boskInstanceID.equals(MDC.get(BOSK_INSTANCE_ID))) {
			throw new AssertionError("MDC bosk instance ID must be " + boskInstanceID + " but was " + MDC.get(BOSK_INSTANCE_ID));
		}
	}
}
