package works.bosk.spring.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Logs a warning when maintenance settings are present but the endpoints are disabled, so that
 * a disabled endpoint is never a silent surprise.
 */
final class MaintenancePropertiesValidator implements InitializingBean {
	private final Environment environment;

	MaintenancePropertiesValidator(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		if (isMisconfigured(environment)) {
			LOGGER.warn(
				"bosk.web-api.maintenance.path or bosk.web-api.maintenance.authority is set, but "
					+ "bosk.web-api.maintenance.access is NONE, so the maintenance endpoints will not be "
					+ "registered. Set bosk.web-api.maintenance.access to UNSECURED, BEARER, or AUTHENTICATED to enable them.");
		}
	}

	static boolean isMisconfigured(Environment environment) {
		if (!isDisabled(environment)) {
			return false;
		}
		return environment.containsProperty("bosk.web-api.maintenance.path")
			|| environment.containsProperty("bosk.web-api.maintenance.authority");
	}

	private static boolean isDisabled(Environment environment) {
		String access = environment.getProperty("bosk.web-api.maintenance.access", MaintenanceAccess.NONE.name());
		return MaintenanceAccess.NONE.name().equalsIgnoreCase(access);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MaintenancePropertiesValidator.class);
}
