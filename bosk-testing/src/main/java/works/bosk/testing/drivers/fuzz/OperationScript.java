package works.bosk.testing.drivers.fuzz;

import java.util.List;
import works.bosk.Bosk;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * A named, replayable sequence of driver operations.
 *
 * <p>A {@link DriverOperation} embeds {@link works.bosk.Reference References} that are bound to a
 * particular bosk, so a sequence cannot be stored as ready-made operations and later replayed
 * against a different bosk. Instead, a script is a <em>recipe</em>: given the bosk under test, it
 * produces the operations, with references bound to that bosk. That is why {@link #operationsFor}
 * takes the reference bosk as an argument rather than returning a stored list, and it is what makes
 * a script portable from the run that recorded it to any later run.
 *
 * <p>Implementations that persist scripts are expected to store paths and serialized values rather
 * than {@code Reference} objects, and to rebuild the operations against the supplied bosk.
 */
public interface OperationScript {

	/**
	 * @return a human-readable name used to identify the script in test output
	 */
	String name();

	/**
	 * @param referenceBosk the bosk whose references the returned operations must use
	 * @return the operations to replay, in order
	 */
	List<DriverOperation> operationsFor(Bosk<TestEntity> referenceBosk) throws InvalidTypeException;
}
