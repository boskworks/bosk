package works.bosk.testing.drivers;

import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectedTest;
import works.bosk.testing.BoskTestUtils;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.drivers.state.UpgradeableEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the ability of a driver to share state between two bosks.
 */
public abstract class SharedDriverConformanceTest extends DriverConformanceTest {

	@Override
	protected void assertCorrectBoskContents() {
		super.assertCorrectBoskContents();
		var latecomer = new Bosk<>(BoskTestUtils.boskName("latecomer"), TestEntity.class, AbstractDriverTest::initialRoot, BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		try {
			latecomer.driver().flush();
		} catch (Exception e) {
			throw new AssertionError("Unexpected exception", e);
		}
		TestEntity expected, actual;
		try (var _ = canonicalBosk.readContext()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (var _ = latecomer.readContext()) {
			actual = latecomer.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	@InjectedTest
	void updateInsidePolyfill_works() throws IOException, InterruptedException, InvalidTypeException {
		// We'll use this as an honest observer of the actual state
		LOGGER.debug("Create Original bosk");
		Bosk<TestEntity> originalBosk = new Bosk<>(
			boskName("Original"),
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		LOGGER.debug("Create Upgradeable bosk");
		@SuppressWarnings({"rawtypes","unchecked"})
		DriverFactory<UpgradeableEntity> upgradeableDriverFactory = (DriverFactory)driverFactory; // Yeehaw! Hope you don't care too much about the root type
		Bosk<UpgradeableEntity> upgradeableBosk = new Bosk<>(
			boskName("Upgradeable"),
			UpgradeableEntity.class,
			(b) -> { throw new AssertionError("upgradeableBosk should use the state from MongoDB"); },
			BoskConfig.<UpgradeableEntity>builder().driverFactory(upgradeableDriverFactory).build());

		LOGGER.debug("Ensure polyfill returns the right value on read");
		TestValues polyfill;
		try (var _ = upgradeableBosk.readContext()) {
			polyfill = upgradeableBosk.rootReference().value().values();
		}
		assertEquals(TestValues.blank(), polyfill);

		LOGGER.debug("Check state before");
		Optional<TestValues> before;
		try (var _ = originalBosk.readContext()) {
			before = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.empty(), before); // Not there yet

		LOGGER.debug("Perform update inside polyfill");
		Refs refs = upgradeableBosk.buildReferences(Refs.class);
		upgradeableBosk.driver().submitReplacement(refs.valuesString(), "new value");
		originalBosk.driver().flush(); // Not the bosk that did the update!

		LOGGER.debug("Check state after");
		String after;
		try (var _ = originalBosk.readContext()) {
			after = originalBosk.rootReference().value().values().get().string();
		}
		assertEquals("new value", after); // Now it's there
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SharedDriverConformanceTest.class);
}
