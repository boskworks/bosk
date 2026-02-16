package works.bosk;

/**
 * Factory for creating {@link BoskDriver} instances for a {@link Bosk}.
 * <p>
 * The driver stack processes updates to the bosk state tree,
 * and forms the basis for customizing a bosk's behaviour,
 * with each driver in the stack having the opportunity to apply
 * its own logic and to pass along (possibly modified) updates to the next driver downstream.
 * This allows layers of functionality to be combined
 * in a predictable and composable way
 * with little or no coordination or even awareness of each other.
 * Concerns like database persistence ({@code MongoDriver}, {@code SqlDriver}),
 * observability ({@code OpenTelemetryDriver}, {@code ReportingDriver}),
 * and diagnostic context propagation ({@code DiagnosticScopeDriver})
 * can each be implemented as orthogonal drivers.
 *
 * @see BoskDriver
 * @see DriverStack
 */
public interface DriverFactory<R extends StateTreeNode> {
	/**
	 * Creates a new {@link BoskDriver} for the given bosk.
	 *
	 * <p>
	 * This method is called once during {@link Bosk} construction. The returned driver
	 * will handle all update operations throughout the bosk's lifetime.
	 *
	 * <p>
	 * Implementations typically:
	 * <ul><li>
	 * store the {@code boskInfo} and/or {@code downstream} references for later use,
	 * </li><li>
	 * create {@link Reference} objects using {@link RootReference#buildReferences(Class) rootReference().buildReferences},
	 * </li><li>
	 * set up any necessary resources (database connections, etc.), and
	 * </li><li>
	 * return a driver with the desired behaviour.
	 * </li></ul>
	 *
	 * @param boskInfo Information about the bosk being constructed, including its root reference,
	 *                 name, and diagnostic context; a kind of stand-in for the {@link Bosk} which is still under construction.
	 * @param downstream The next driver in the stack. Usually most updates are delegated to this driver
	 *                   so updates ultimately reach the bosk's in-memory state.
	 * @return a new {@link BoskDriver} instance that will handle updates for the bosk
	 */
	BoskDriver build(BoskInfo<R> boskInfo, BoskDriver downstream);
}
