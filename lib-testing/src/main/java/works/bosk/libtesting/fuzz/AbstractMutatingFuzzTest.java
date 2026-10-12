package works.bosk.libtesting.fuzz;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import works.bosk.DriverFactory;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Randomly mutates scripts drawn from a seed corpus and replays the results, checking each against
 * the canonical bosk.
 *
 * <p>This is mutation-based fuzzing: rather than generating sequences from scratch, it perturbs
 * sequences that are already known to be interesting (see {@link ScriptMutator}) and uses the same
 * oracle as the other randomized harnesses. Every iteration uses a fresh bosk. The whole run is
 * driven by a single seeded {@link Random}, so a failure can be reproduced from the seed reported
 * in the failure message.
 */
public abstract class AbstractMutatingFuzzTest extends AbstractDriverTest {

	/**
	 * @return the driver under test
	 */
	protected abstract DriverFactory<TestEntity> driverFactory();

	/**
	 * @return the scripts to mutate
	 */
	protected abstract List<OperationScript> seedCorpus() throws InvalidTypeException, IOException, InterruptedException;

	/**
	 * @return how many mutated scripts to replay
	 */
	protected int iterations() {
		return 100;
	}

	/**
	 * @return the seed controlling which scripts are mutated and how
	 */
	protected long seed() {
		return 1L;
	}

	/**
	 * Called before each iteration so that subclasses whose drivers hold resources or persist
	 * state between iterations can release or clear it. The default implementation does nothing.
	 */
	protected void beforeIteration() { }

	@Test
	void mutateAndReplay() throws InvalidTypeException, IOException, InterruptedException {
		List<OperationScript> corpus = seedCorpus();
		Random random = new Random(seed());
		for (int i = 0; i < iterations(); i++) {
			OperationScript script = ScriptMutator.mutate(corpus.get(random.nextInt(corpus.size())), random);
			assertDoesNotThrow(
				() -> {
					replay(script);
					return null;
				},
				() -> "Mutated script \"" + script.name() + "\" failed");
		}
	}

	private void replay(OperationScript script) throws IOException, InterruptedException, InvalidTypeException {
		beforeIteration();
		setupBosksAndReferences(driverFactory());
		for (DriverOperation operation : script.operationsFor(bosk)) {
			operation.submitTo(driver);
			driver.flush();
		}
		assertCorrectBoskContents();
	}
}
