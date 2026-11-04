package works.bosk.testing.drivers.fuzz;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.DriverFactory;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Replays a fixed set of {@link OperationScript}s against a driver and checks each one against the
 * canonical bosk, using the same oracle as
 * {@link AbstractRandomizedDriverConformanceTest}.
 *
 * <p>This is the deterministic counterpart to randomized exploration. A subclass supplies a
 * {@link DriverFactory} and a list of scripts; each script is replayed against a freshly
 * initialized bosk. Because a script is a recipe rather than a list of ready-made operations, the
 * same script can be used no matter which bosk instance it runs against, and replaying it is
 * entirely free of randomness.
 */
public abstract class AbstractFuzzRegressionTest extends AbstractDriverTest {

	/**
	 * @return the driver under test
	 */
	protected abstract DriverFactory<TestEntity> driverFactory();

	/**
	 * @return the scripts to replay
	 */
	protected abstract List<OperationScript> scripts() throws InvalidTypeException;

	/**
	 * Called before each script is replayed, so that subclasses whose drivers hold resources or
	 * persist state between scripts can release or clear it. The default implementation does
	 * nothing.
	 */
	protected void beforeScript() { }

	@Test
	void replayScripts() throws InvalidTypeException {
		for (OperationScript script : scripts()) {
			assertDoesNotThrow(
				() -> {
					replay(script);
					return null;
				},
				() -> "Script \"" + script.name() + "\" failed");
		}
	}

	private void replay(OperationScript script) throws IOException, InterruptedException, InvalidTypeException {
		beforeScript();
		setupBosksAndReferences(driverFactory());
		for (DriverOperation operation : script.operationsFor(bosk)) {
			operation.submitTo(driver);
			driver.flush();
		}
		assertCorrectBoskContents();
	}
}
