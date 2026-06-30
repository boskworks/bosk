package works.bosk.testing.drivers;

import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskConfig.TenancyModel.Fixed;
import works.bosk.BoskConfig.TenancyModel.Persistent;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskContext.Tenant.TenantId;
import works.bosk.BoskDriver.EntireState;
import works.bosk.DriverFactory;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.util.PerTenant;

import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the ability of a driver to share state between multiple Bosks.
 * <p>
 * Each call to {@link #assertCorrectBoskContents()} checks two additional
 * bosks: one long-lived one, and one that is newly created immediately before
 * the assertion.
 */
public abstract class SharedDriverConformanceTest extends DriverConformanceTest {
	final TenantId tenant1 = Tenant.setTo(TENANT1);
	final TenantId tenant2 = Tenant.setTo(TENANT2);

	Bosk<TestEntity> remoteBosk;

	@Override
	protected void setupBosksAndReferences(DriverFactory<TestEntity> driverFactory) {
		super.setupBosksAndReferences(driverFactory);
		remoteBosk = new Bosk<>(
			boskName("remote"),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(driverFactory)
				.tenancyModel(scenario.tenancyModel)
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
				.driverFactory(driverFactory)
				.tenancyModel(scenario.tenancyModel)
				.build());
		assertSameBoskContents(latecomer);
	}

	private void assertSameBoskContents(Bosk<TestEntity> otherBosk) {
		try {
			otherBosk.driver().flush();
		} catch (Exception e) {
			throw new AssertionError("Unexpected exception", e);
		}
		EntireState<TestEntity> expected, actual;
		try (
			var _ = canonicalBosk.readSession()
		) {
			expected = canonicalBosk.entireState();
		}
		try (
			var _ = otherBosk.readSession()
		) {
			actual = otherBosk.entireState();
		}
		if (scenario.tenancyModel instanceof Fixed(var id)) {
			// Either a single tenant or NoTenant is ok. Normalize before comparing
			TenantId tenantId = Tenant.setTo(id);
			var e = PerTenant.from(expected, identity()).asNoTenant(tenantId);
			var a = PerTenant.from(actual, identity()).asNoTenant(tenantId);
			assertEquals(e, a);
		} else {
			// Must be exactly equal
			assertEquals(expected, actual);
		}
	}

	@Test
	void multiTenant_replicatesStateAcrossTenants() throws Exception {
		setupBosksAndReferences(driverFactory);
		Refs refs = bosk.buildReferences(Refs.class);

		// Note: do this assume after the setup is done, or cleanup will crash!
		assumeTrue(scenario.tenancyModel instanceof Persistent);

		tenantScope.close();
		tenantScope = null;

		EntireState.MultiTree<TestEntity> expectedState = (EntireState.MultiTree<TestEntity>) initialState(bosk);
		assertNotEquals(
			expectedState.tenantRoots().get(tenant1),
			expectedState.tenantRoots().get(tenant2),
			"Meta-assertion: the tests won't detect problems if the tenant states are indistinguishable");

		try (var _ = bosk.readSession()) {
			assertEquals(expectedState, bosk.entireState(), "Entire state should be correct");

			// Check that each tenant has its own state
			try (var _ = bosk.context().withTenant(tenant1)) {
				var expected = expectedState.tenantRoots().get(tenant1);
				assertEquals(expected, bosk.rootReference().value());
			}

			try (var _ = bosk.context().withTenant(tenant2)) {
				var expected = expectedState.tenantRoots().get(tenant2);
				assertEquals(expected, bosk.rootReference().value());
			}
		}

		// Change them in different ways

		try (var _ = bosk.context().withTenant(tenant1)) {
			driver.submitReplacement(refs.string(), "new tenant1 string");
		}
		try (var _ = bosk.context().withTenant(tenant2)) {
			driver.submitReplacement(refs.string(), "new tenant2 string");
		}
		driver.flush();

		// Ensure each tenant sees its own change

		try (var _ = bosk.readSession()) {
			try (var _ = bosk.context().withTenant(tenant1)) {
				assertEquals("new tenant1 string", refs.string().value());
			}

			try (var _ = bosk.context().withTenant(tenant2)) {
				assertEquals("new tenant2 string", refs.string().value());
			}
		}
	}
}
