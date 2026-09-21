package works.bosk.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import works.bosk.Bosk;
import works.bosk.jackson.BoskJacksonModule;
import works.bosk.jackson.JacksonSerializer;

@Configuration
public class BoskAutoConfiguration {
	@Bean
	@ConditionalOnProperty(
		prefix = "bosk.web-api",
		name = "read-session",
		matchIfMissing = true)
	@ConditionalOnBean(Bosk.class) // Because of matchIfMissing
	ReadSessionFilter readSessionFilter(
		Bosk<?> bosk
	) {
		return new ReadSessionFilter(bosk);
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
