package works.bosk.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bosk.web")
public record WebProperties(
	Boolean readContext,
	String maintenancePath
) {}
