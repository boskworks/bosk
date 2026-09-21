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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static works.bosk.testing.BoskTestUtils.boskName;

@SpringBootTest(
	classes = MaintenanceEndpointsSecurityTest.TestConfig.class,
	properties = {
		"bosk.web-api.maintenance.access=AUTHENTICATED",
		"bosk.web-api.maintenance.path=/bosk/state"
	})
@AutoConfigureMockMvc
class MaintenanceEndpointsSecurityTest {
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
		 * Permits every request so that the maintenance endpoints' own authorization check is
		 * what decides access.
		 */
		@Bean
		SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
			http
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.httpBasic(Customizer.withDefaults());
			return http.build();
		}
	}

	@Test
	void anonymous_isUnauthorized() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("other"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void authenticatedWithAuthority_isAllowed() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state"))))
			.andExpect(status().isOk());
	}

	@Test
	void modifyWithoutCsrfToken_isForbidden() throws Exception {
		mockMvc.perform(put("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state")))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"plain\",\"name\":\"updated\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void modifyWithCsrfToken_isAccepted() throws Exception {
		mockMvc.perform(put("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state")))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"plain\",\"name\":\"updated\"}"))
			.andExpect(status().isAccepted());
	}

	@Test
	void modifyWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(put("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("other")))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"plain\",\"name\":\"updated\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void deleteWithoutCsrfToken_isForbidden() throws Exception {
		mockMvc.perform(delete("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void deleteWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(delete("/bosk/state/targets/plain")
				.with(user("tester").authorities(new SimpleGrantedAuthority("other")))
				.with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void nonMaintenancePath_isUnaffected() throws Exception {
		mockMvc.perform(get("/other")
				.with(user("tester").authorities(new SimpleGrantedAuthority("bosk:state"))))
			.andExpect(status().isNotFound());
	}
}
