package works.bosk.load;

import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.Reference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * A workload that the {@link LoadRunner} can drive against a {@link Bosk}.
 *
 * <p>The workload owns the state shape and the operation stream. It is initialized in two
 * phases: {@link #initialRoot} is called while the Bosk is being constructed, and
 * {@link #initialize} is called once the Bosk exists, which is where references can be built.
 */
public interface Workload {

	/**
	 * Builds the root object supplied to the Bosk's initial state.
	 */
	TestEntity initialRoot(Bosk<TestEntity> bosk) throws InvalidTypeException;

	/**
	 * Prepares the workload now that the Bosk exists.
	 */
	void initialize(Bosk<TestEntity> bosk) throws InvalidTypeException;

	/**
	 * Submits one update. Called concurrently from every worker, so implementations must not
	 * share mutable state between {@code threadIndex} values unless it is safe to do so.
	 */
	void submit(BoskDriver driver, int threadIndex, long iteration);

	/**
	 * The entity at the given index, for reader threads.
	 *
	 * <p>Indexes are taken modulo the entity count, so a run with more readers than entities is
	 * still well defined.
	 *
	 * @throws InvalidTypeException if the state type does not match the workload's assumption
	 */
	Reference<TestEntity> entity(Bosk<TestEntity> bosk, int index) throws InvalidTypeException;

	/**
	 * Verifies that the final state matches what the submitted updates should have produced.
	 *
	 * <p>Implementations should compare the targets they own rather than the whole state, so that
	 * verification does not allocate a second copy of a large catalog and can name the target that
	 * does not match.
	 *
	 * @throws AssertionError if verification itself cannot be performed
	 */
	Verification verify(Bosk<TestEntity> bosk);
}
