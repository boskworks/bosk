package works.bosk.testing.drivers;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.drivers.state.UpgradeableEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests that a driver can use {@link works.bosk.annotations.Polyfill @Polyfill}
 * to share state between two bosks with different but compatible root types.
 */
public abstract class PolyfillDriverConformanceTest extends SharedDriverConformanceTest {
	@Test
	void updateInsidePolyfill_works() throws IOException, InterruptedException, InvalidTypeException {
		// We'll use this as an honest observer of the actual state
		LOGGER.debug("Create Original bosk");
		Bosk<TestEntity> originalBosk = new Bosk<>(
			boskName("Original"),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.tenancyModel(scenario.tenancyModel)
				.driverFactory(driverFactory)
				.build());

		LOGGER.debug("Create Upgradeable bosk");
		@SuppressWarnings({"rawtypes","unchecked"})
		DriverFactory<UpgradeableEntity> upgradeableDriverFactory = (DriverFactory)driverFactory; // Yeehaw! Hope you don't care too much about the root type
		Bosk<UpgradeableEntity> upgradeableBosk = new Bosk<>(
			boskName("Upgradeable"),
			UpgradeableEntity.class,
			_ -> { throw new AssertionError("upgradeableBosk should use the state from MongoDB"); },
			BoskConfig.<UpgradeableEntity>builder().driverFactory(upgradeableDriverFactory).build());

		LOGGER.debug("Ensure polyfill returns the right value on read");
		TestValues polyfill;
		try (var _ = upgradeableBosk.readSession()) {
			polyfill = upgradeableBosk.rootReference().value().values();
		}
		assertEquals(TestValues.blank(), polyfill);

		LOGGER.debug("Check state before");
		Optional<TestValues> before;
		try (var _ = originalBosk.readSession()) {
			before = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.empty(), before); // Not there yet

		LOGGER.debug("Perform update inside polyfill");
		Refs refs = upgradeableBosk.buildReferences(Refs.class);
		upgradeableBosk.driver().submitReplacement(refs.valuesString(), "new value");
		originalBosk.driver().flush(); // Not the bosk that did the update!

		LOGGER.debug("Check state after");
		String after;
		try (var _ = originalBosk.readSession()) {
			after = originalBosk.rootReference().value().values().get().string();
		}
		assertEquals("new value", after); // Now it's there
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(PolyfillDriverConformanceTest.class);
}
