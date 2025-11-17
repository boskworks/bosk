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
 * A driver that silently ignores all updates.
 * Mainly useful for testing, for example,
 * to make a driver that responds to a subset of updates
 * by overriding the corresponding methods.
 */
public class NoOpDriver implements BoskDriver {
	public static <RR extends StateTreeNode> DriverFactory<RR> factory() {
		return (_,_) -> new NoOpDriver();
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		throw new UnsupportedOperationException();
	}

	@Override public <T> void submitReplacement(Reference<T> target, T newValue) { }
	@Override public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) { }
	@Override public <T> void submitConditionalCreation(Reference<T> target, T newValue) { }
	@Override public <T> void submitDeletion(Reference<T> target) { }
	@Override public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) { }
	@Override public void flush() throws IOException, InterruptedException { }
}
