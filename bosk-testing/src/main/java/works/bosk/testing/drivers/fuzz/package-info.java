/**
 * Randomized sequence testing for Bosk drivers.
 *
 * <p>Where {@link works.bosk.testing.drivers.DriverConformanceTest} exercises a fixed,
 * hand-written set of operations, this package generates pseudorandom sequences of
 * {@link works.bosk.testing.drivers.operations.DriverOperation}s and checks the result against the
 * same canonical-bosk oracle. The point is to reach combinations of updates that nobody thought to
 * write down, while keeping every run reproducible from a seed.
 *
 * <p>The two halves of the design are deliberately separate:
 *
 * <ul>
 *     <li>{@link works.bosk.testing.drivers.fuzz.OperationSequenceGenerator} decides
 *     <em>what</em> to do, consulting the current state so that each operation targets a path that
 *     exists, and using an
 *     {@link works.bosk.testing.drivers.fuzz.OperationChooser} to decide the shape of each change.
 *     The chooser is the seam where a future profile-guided discovery mode could apply feedback.</li>
 *     <li>{@link works.bosk.testing.drivers.fuzz.AbstractRandomizedDriverConformanceTest} decides
 *     <em>how</em> to run, replaying several seeded batches against a driver and asserting
 *     correctness with the existing {@link works.bosk.testing.drivers.AbstractDriverTest}
 *     machinery.</li>
 * </ul>
 *
 * <p>A subclass supplies only a
 * {@link works.bosk.DriverFactory}, so the suite is as straightforward to apply to a new driver as
 * the hand-written conformance tests are.
 */
package works.bosk.testing.drivers.fuzz;
