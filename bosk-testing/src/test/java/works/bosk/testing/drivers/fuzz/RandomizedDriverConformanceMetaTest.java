package works.bosk.testing.drivers.fuzz;

import works.bosk.DriverFactory;
import works.bosk.testing.drivers.state.TestEntity;

import static works.bosk.BoskConfig.simpleDriver;

/**
 * Makes sure {@link AbstractRandomizedDriverConformanceTest} works properly by running it against
 * a known-good driver, analogously to
 * {@link works.bosk.testing.drivers.ConformanceMetaTest}.
 */
class RandomizedDriverConformanceMetaTest extends AbstractRandomizedDriverConformanceTest {

	@Override
	protected DriverFactory<TestEntity> driverFactory() {
		return simpleDriver();
	}

}
