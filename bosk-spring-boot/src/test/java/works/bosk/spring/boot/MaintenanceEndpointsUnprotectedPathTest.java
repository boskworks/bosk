package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.StateTreeNode;
import works.bosk.jackson.JacksonSerializer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * If an application's security filter chains do not cover the maintenance path, no security
 * filters run for it. There is no authentication in the security context and no entry point to
 * challenge the caller, so the endpoints deny access with a plain 403.
 */
@SpringBootTest(
	classes = MaintenanceEndpointsUnprotectedPathTest.TestConfig.class,
	properties = {
		"bosk.web-api.maintenance.access=AUTHENTICATED",
		"bosk.web-api.maintenance.path=/bosk/state"
	})
@AutoConfigureMockMvc
class MaintenanceEndpointsUnprotectedPathTest {
	@Autowired
	MockMvc mockMvc;

	public record Target(Identifier id, String name) implements Entity {}

	public record State(Catalog<Target> targets) implements StateTreeNode {}

	@Configuration
	@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
	static class TestConfig {
		@Bean
		Bosk<State> bosk() {
			return new Bosk<>(
				boskName(),
				State.class,
				_ -> new State(Catalog.of(new Target(Identifier.from("plain"), "plain"))),
				BoskConfig.simple()
			);
		}

		@Bean
		ObjectMapper objectMapper(Bosk<State> bosk, JacksonSerializer jacksonSerializer) {
			return JsonMapper.builder()
				.addModule(jacksonSerializer.moduleFor(bosk))
				.build();
		}

		/**
		 * Deliberately does not match the maintenance path, leaving it unprotected by any chain.
		 */
		@Bean
		SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
			http
				.securityMatcher("/other/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.httpBasic(Customizer.withDefaults());
			return http.build();
		}
	}

	@Test
	void unprotectedPath_isDenied() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain"))
			.andExpect(status().isForbidden());
	}
}
