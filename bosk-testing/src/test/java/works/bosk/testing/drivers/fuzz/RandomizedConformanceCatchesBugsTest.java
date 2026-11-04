package works.bosk.testing.drivers.fuzz;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskConfig.simpleDriver;

/**
 * Confirms that the randomized conformance harness actually detects a misbehaving driver.
 *
 * <p>A test suite that passes against a broken driver is worse than useless, so this meta-test
 * points {@link AbstractRandomizedDriverConformanceTest} at a driver that discards every update
 * and asserts that the harness fails. The chooser is forced to always create, so the very first
 * operation is guaranteed to be a dropped update.
 */
class RandomizedConformanceCatchesBugsTest {

	@Test
	void harnessRejectsDriverThatDropsUpdates() {
		var harness = new AbstractRandomizedDriverConformanceTest() {
			@Override
			protected DriverFactory<TestEntity> driverFactory() {
				return droppingUpdates(simpleDriver());
			}

			@Override
			protected OperationChooser chooser() {
				// The applicable list always begins with CREATE, so this
				// deterministically exercises a dropped update on the first operation.
				return (random, applicable) -> applicable.get(0);
			}
		};
		assertThrows(AssertionError.class, harness::randomizedSequences);
	}

	private static DriverFactory<TestEntity> droppingUpdates(DriverFactory<TestEntity> delegate) {
		return (boskInfo, downstream) -> {
			BoskDriver real = delegate.build(boskInfo, downstream);
			return new BoskDriver() {
				@Override
				public <R extends StateTreeNode> R initialState() throws InvalidTypeException, IOException, InterruptedException {
					return real.initialState();
				}

				@Override
				public <T> void submitReplacement(Reference<T> target, T newValue) { }

				@Override
				public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) { }

				@Override
				public <T> void submitConditionalCreation(Reference<T> target, T newValue) { }

				@Override
				public <T> void submitDeletion(Reference<T> target) { }

				@Override
				public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) { }

				@Override
				public void flush() throws IOException, InterruptedException {
					real.flush();
				}
			};
		};
	}

}
