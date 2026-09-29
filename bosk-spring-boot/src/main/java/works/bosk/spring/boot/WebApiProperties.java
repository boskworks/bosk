package works.bosk.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code bosk-spring-boot} web integration, under the {@code bosk.web-api}
 * prefix. {@link #maintenance()} configures the {@link MaintenanceEndpoints}, and
 * {@link #readSession()} configures the {@link ReadSessionFilter}.
 */
@ConfigurationProperties(prefix = "bosk.web-api")
public record WebApiProperties(
	Boolean readSession,
	Maintenance maintenance
) {
	/**
	 * The default path for the maintenance endpoints. Shared by these properties, the endpoint
	 * mapping, and {@link BoskMaintenanceRequestMatcher} so they cannot drift apart.
	 */
	static final String DEFAULT_MAINTENANCE_PATH = "/bosk/state";

	public WebApiProperties {
		if (maintenance == null) {
			maintenance = new Maintenance(null, null, null);
		}
	}

	public record Maintenance(
		MaintenanceAccess access,
		String path,
		String authority
	) {
		public Maintenance {
			if (access == null) {
				access = MaintenanceAccess.NONE;
			}
			if (path == null || path.isBlank()) {
				path = DEFAULT_MAINTENANCE_PATH;
			}
			if (authority == null) {
				authority = "bosk:state";
			}
		}
	}
}
