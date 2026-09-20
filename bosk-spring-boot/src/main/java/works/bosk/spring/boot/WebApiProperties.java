package works.bosk.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bosk.web-api")
public record WebApiProperties(
	Boolean readSession,
	String maintenancePath
) {}
