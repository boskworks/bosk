package works.bosk.testing.drivers.fuzz;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import works.bosk.DriverFactory;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Replays several batches of pseudorandom {@link DriverOperation} sequences against a driver,
 * checking after each batch that the resulting state matches the canonical bosk.
 *
 * <p>This is the randomized counterpart to
 * {@link works.bosk.testing.drivers.DriverConformanceTest}: a subclass supplies only a
 * {@link DriverFactory}, and inherits a single test method that runs
 * {@link #batchCount()} independent batches of {@link #sequenceLength()} operations each. Every
 * batch uses a fresh bosk so that batches cannot interfere with each other.
 *
 * <p>Each batch is seeded with {@code seed() + batch}, so a failure can be reproduced by running
 * a single batch with the same effective seed; the seed is reported in the failure message.
 * Operations are generated against the canonical (known-correct) bosk's state, so the sequence
 * keeps making sense even when the driver under test misbehaves. The driver is flushed after each
 * operation so that the model the generator reads is up to date.
 *
 * <p>Determinism note: the same driver, seed, and chooser produce the same sequence. The test does
 * not assert anything about the random distribution itself, only that each batch ends in a correct
 * state.
 */
public abstract class AbstractRandomizedDriverConformanceTest extends AbstractDriverTest {

	/**
	 * @return the driver under test
	 */
	protected abstract DriverFactory<TestEntity> driverFactory();

	@Test
	void randomizedSequences() {
		for (int batch = 0; batch < batchCount(); batch++) {
			long batchSeed = seed() + batch;
			assertDoesNotThrow(
				() -> {
					runBatch(batchSeed);
					return null;
				},
				() -> "Randomized conformance batch failed with seed " + batchSeed);
		}
	}

	/**
	 * @return how many independent seeded batches to run
	 */
	protected int batchCount() {
		return 100;
	}

	/**
	 * @return how many operations to perform in each batch
	 */
	protected int sequenceLength() {
		return 10;
	}

	/**
	 * @return the base seed; batch {@code b} uses {@code seed() + b}
	 */
	protected long seed() {
		return 1L;
	}

	/**
	 * @return the strategy used to choose each operation; override to influence generation
	 */
	protected OperationChooser chooser() {
		return OperationChooser.random();
	}

	/**
	 * Called before each batch so that subclasses whose drivers hold resources or persist state
	 * between batches can release or clear it. The default implementation does nothing.
	 */
	protected void beforeBatch() { }

	private void runBatch(long batchSeed) throws IOException, InterruptedException, InvalidTypeException {
		beforeBatch();
		setupBosksAndReferences(driverFactory());
		OperationSequenceGenerator generator =
			new OperationSequenceGenerator(new Random(batchSeed), bosk, canonicalBosk, chooser());
		for (int i = 0; i < sequenceLength(); i++) {
			DriverOperation operation = generator.nextOperation();
			operation.submitTo(driver);
			// Let the driver settle so the generator sees the effect of this operation
			// when it consults the model before producing the next one.
			driver.flush();
		}
		assertCorrectBoskContents();
	}
}
