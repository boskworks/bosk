package works.bosk.testing.drivers;

import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.testing.BoskTestUtils;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the ability of a driver to share state between two bosks.
 */
public abstract class SharedDriverConformanceTest extends DriverConformanceTest {

	@Override
	protected void assertCorrectBoskContents() {
		super.assertCorrectBoskContents();
		var latecomer = new Bosk<>(
			BoskTestUtils.boskName("latecomer"),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(driverFactory)
				.tenancyModel(scenario.tenancyModel)
				.build());
		try {
			latecomer.driver().flush();
		} catch (Exception e) {
			throw new AssertionError("Unexpected exception", e);
		}
		TestEntity expected, actual;
		try (var _ = canonicalBosk.readSession()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (var _ = latecomer.readSession()) {
			actual = latecomer.rootReference().value();
		}
		assertEquals(expected, actual);
	}

}
