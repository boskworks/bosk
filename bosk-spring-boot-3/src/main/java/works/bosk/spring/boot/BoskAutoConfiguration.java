package works.bosk.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import works.bosk.Bosk;
import works.bosk.jackson.BoskJacksonModule;
import works.bosk.jackson.JacksonSerializer;

@Configuration
@EnableConfigurationProperties(WebProperties.class)
public class BoskAutoConfiguration {
	@Bean
	@ConditionalOnProperty(
		prefix = "bosk.web",
		name = "read-context",
		matchIfMissing = true)
	@ConditionalOnBean(Bosk.class) // Because of matchIfMissing
	ReadContextFilter readContextFilter(
		Bosk<?> bosk
	) {
		return new ReadContextFilter(bosk);
	}

	@Bean
	@ConditionalOnProperty(prefix = "bosk.web", name = "maintenance-path")
	MaintenanceEndpoints maintenanceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonSerializer jackson
	) {
		return new MaintenanceEndpoints(bosk, mapper, jackson);
	}

	@Bean
	@ConditionalOnMissingBean
	JacksonSerializer jacksonSerializer() {
		return new JacksonSerializer();
	}

	@Bean
	BoskJacksonModule boskJacksonModule(Bosk<?> bosk, JacksonSerializer jacksonSerializer) {
		return jacksonSerializer.moduleFor(bosk);
	}

}
