package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import org.junit.jupiter.api.BeforeEach;
import works.bosk.BoskDriver;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

/**
 * All operations are idempotent, even including {@code flush},
 * so it ought to be ok to send each one multiple times in succession.
 */
public class RepeatingDriverConformanceTest extends DriverConformanceTest {
	static final int REPS = 3;

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = (_,downstream) -> new BoskDriver() {
			@Override
			public StateTreeNode initialState(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
				return downstream.initialState(rootType);
			}

			@Override
			public <T> void submitReplacement(Reference<T> target, T newValue) {
				for (int i = 0; i < REPS; i++) {
					downstream.submitReplacement(target, newValue);
				}
			}

			@Override
			public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
				for (int i = 0; i < REPS; i++) {
					downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
				}
			}

			@Override
			public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
				for (int i = 0; i < REPS; i++) {
					downstream.submitConditionalCreation(target, newValue);
				}
			}

			@Override
			public <T> void submitDeletion(Reference<T> target) {
				for (int i = 0; i < REPS; i++) {
					downstream.submitDeletion(target);
				}
			}

			@Override
			public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
				for (int i = 0; i < REPS; i++) {
					downstream.submitConditionalDeletion(target, precondition, requiredValue);
				}
			}

			@Override
			public void flush() throws IOException, InterruptedException {
				for (int i = 0; i < REPS; i++) {
					downstream.flush();
				}
			}
		};
	}

}
