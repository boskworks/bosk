package works.bosk.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bosk.web-api")
public record WebApiProperties(
	Boolean readSession,
	Maintenance maintenance
) {
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
			if (path == null) {
				path = "/bosk/state";
			}
			if (authority == null) {
				authority = "bosk:state";
			}
		}
	}
}
