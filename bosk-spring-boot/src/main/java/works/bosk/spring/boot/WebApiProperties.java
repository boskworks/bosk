package works.bosk.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the {@code bosk-spring-boot} web integration, under the {@code bosk.web-api}
 * prefix. {@link #maintenance()} configures the {@link MaintenanceEndpoints}, and
 * {@link #readSession()} configures the {@link ReadSessionFilter}.
 */
@ConfigurationProperties(prefix = "bosk.web-api")
public record WebApiProperties(
	Boolean readSession,
	@DefaultValue Maintenance maintenance
) {
	/**
	 * The default path for the maintenance endpoints. Shared by these properties, the endpoint
	 * mapping, and {@link BoskMaintenanceRequestMatcher} so they cannot drift apart.
	 */
	static final String DEFAULT_MAINTENANCE_PATH = "/bosk/state";

	public record Maintenance(
		@DefaultValue("NONE") MaintenanceAccess access,
		@DefaultValue(DEFAULT_MAINTENANCE_PATH) String path,
		@DefaultValue("bosk:state") String authority
	) {}
}
