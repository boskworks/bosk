package works.bosk.testing.drivers.fuzz;

/**
 * The kinds of operation that {@link OperationSequenceGenerator} knows how to produce.
 *
 * <p>The set is deliberately a small, high-level vocabulary rather than one constant per
 * {@link works.bosk.testing.drivers.operations.DriverOperation} subtype: a generator doesn't care
 * about the exact record type, only about the shape of change it wants to make, and the choice of
 * {@code OperationKind} is the decision most likely to be influenced by some future profile-guided
 * discovery mode.
 */
public enum OperationKind {
	/**
	 * Create a new entity in the root catalog.
	 */
	CREATE,

	/**
	 * Replace a single field of an existing entity.
	 */
	REPLACE_FIELD,

	/**
	 * Replace a whole existing entity.
	 */
	REPLACE_ENTITY,

	/**
	 * Delete an existing entity.
	 */
	DELETE,

	/**
	 * Replace an existing entity, but only if a precondition holds.
	 */
	CONDITIONAL_REPLACE,

	/**
	 * Delete an existing entity, but only if a precondition holds.
	 */
	CONDITIONAL_DELETE,

	/**
	 * Flush the driver.
	 */
	FLUSH
}
