package works.bosk.testing.drivers;

import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the ability of a driver to share state between multiple Bosks.
 * <p>
 * Each call to {@link #assertCorrectBoskContents()} checks two additional
 * bosks: one long-lived one, and one that is newly created immediately before
 * the assertion.
 */
public abstract class SharedDriverConformanceTest extends DriverConformanceTest {
	Bosk<TestEntity> remoteBosk;

	@Override
	protected void setupBosksAndReferences(DriverFactory<TestEntity> driverFactory) {
		super.setupBosksAndReferences(driverFactory);
		remoteBosk = new Bosk<>(
			boskName("remote"),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(MdcCheckingDriver.wrap(driverFactory))
				.build());
	}

	@Override
	protected void assertCorrectBoskContents() {
		super.assertCorrectBoskContents();
		assertSameBoskContents(remoteBosk);

		var latecomer = new Bosk<>(
			boskName("latecomer"),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(MdcCheckingDriver.wrap(driverFactory))
				.build());
		assertSameBoskContents(latecomer);
	}

	private void assertSameBoskContents(Bosk<TestEntity> otherBosk) {
		try {
			otherBosk.driver().flush();
		} catch (Exception e) {
			throw new AssertionError("Unexpected exception", e);
		}
		TestEntity expected, actual;
		try (
			var _ = canonicalBosk.readSession()
		) {
			expected = canonicalBosk.rootReference().value();
		}
		try (
			var _ = otherBosk.readSession()
		) {
			actual = otherBosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

}
