package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * {@link MaintenanceAccess#BEARER} accepts only requests that carry a bearer token, and because
 * a browser does not attach a bearer token automatically, it does not need CSRF protection.
 */
@SpringBootTest(
	classes = MaintenanceEndpointsBearerTest.TestConfig.class,
	properties = {
		"bosk.web-api.maintenance.access=BEARER",
		"bosk.web-api.maintenance.path=/bosk/state"
	})
@AutoConfigureMockMvc
class MaintenanceEndpointsBearerTest {
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

		@Bean
		SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
			http
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.httpBasic(Customizer.withDefaults());
			return http.build();
		}
	}

	@Test
	void bearerGet_isAllowed() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state")))
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk());
	}

	@Test
	void bearerPut_isAcceptedWithoutCsrfToken() throws Exception {
		mockMvc.perform(put("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state")))
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"plain\",\"name\":\"updated\"}"))
			.andExpect(status().isAccepted());
	}

	@Test
	void authenticatedWithoutBearerToken_isForbidden() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void bearerWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("other")))
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isForbidden());
	}

	@Test
	void anonymous_isUnauthorized() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain"))
			.andExpect(status().isUnauthorized());
	}
}
