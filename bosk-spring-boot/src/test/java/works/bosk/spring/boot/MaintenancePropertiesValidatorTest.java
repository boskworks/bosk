package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenancePropertiesValidatorTest {
	@Test
	void nothingSet_isNotMisconfigured() {
		assertFalse(MaintenancePropertiesValidator.isMisconfigured(new MockEnvironment()));
	}

	@Test
	void pathSetWithoutAccess_isMisconfigured() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("bosk.web-api.maintenance.path", "/custom");
		assertTrue(MaintenancePropertiesValidator.isMisconfigured(environment));
	}

	@Test
	void authoritySetWithoutAccess_isMisconfigured() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("bosk.web-api.maintenance.authority", "custom:authority");
		assertTrue(MaintenancePropertiesValidator.isMisconfigured(environment));
	}

	@Test
	void pathSetWithNoneAccess_isMisconfigured() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("bosk.web-api.maintenance.access", "NONE")
			.withProperty("bosk.web-api.maintenance.path", "/custom");
		assertTrue(MaintenancePropertiesValidator.isMisconfigured(environment));
	}

	@Test
	void pathSetWithEnabledAccess_isNotMisconfigured() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("bosk.web-api.maintenance.access", "UNSECURED")
			.withProperty("bosk.web-api.maintenance.path", "/custom");
		assertFalse(MaintenancePropertiesValidator.isMisconfigured(environment));
	}
}
