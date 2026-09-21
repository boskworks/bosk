package works.bosk.spring.boot;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * {@link MaintenanceAccess#BEARER} requires a bearer token and, because a browser does not
 * attach one automatically, does not need CSRF protection.
 * <p>
 * The security configuration here authenticates tokens the way an application would, using
 * Spring Security's opaque-token introspection with a stub introspector, so these tests exercise
 * the mode as it is actually configured rather than only the endpoint's own header check.
 */
@SpringBootTest(
	classes = MaintenanceEndpointsBearerTest.TestConfig.class,
	properties = {
		"bosk.web-api.maintenance.access=BEARER",
		"bosk.web-api.maintenance.path=/bosk/state"
	})
@AutoConfigureMockMvc
class MaintenanceEndpointsBearerTest {
	static final String GOOD_TOKEN = "good-token";
	static final String OTHER_TOKEN = "other-token";

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
		OpaqueTokenIntrospector introspector() {
			return token -> {
				if (GOOD_TOKEN.equals(token)) {
					return principal("bosk:state");
				} else if (OTHER_TOKEN.equals(token)) {
					return principal("other");
				} else {
					throw new BadOpaqueTokenException("Unknown token");
				}
			};
		}

		private static OAuth2AuthenticatedPrincipal principal(String authority) {
			return new DefaultOAuth2AuthenticatedPrincipal(
				"tester",
				Map.of("sub", "tester"),
				List.of(new SimpleGrantedAuthority(authority)));
		}

		@Bean
		SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			OpaqueTokenIntrospector introspector
		) throws Exception {
			http
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(
					opaque -> opaque.introspector(introspector)));
			return http.build();
		}
	}

	@Test
	void bearerGet_isAllowed() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.header("Authorization", "Bearer " + GOOD_TOKEN))
			.andExpect(status().isOk());
	}

	@Test
	void bearerPut_isAcceptedWithoutCsrfToken() throws Exception {
		mockMvc.perform(put("/bosk/state/targets/plain")
				.header("Authorization", "Bearer " + GOOD_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"plain\",\"name\":\"updated\"}"))
			.andExpect(status().isAccepted());
	}

	@Test
	void withoutBearerToken_isUnauthorized() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidToken_isUnauthorized() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.header("Authorization", "Bearer not-a-token"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void bearerWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(get("/bosk/state/targets/plain")
				.header("Authorization", "Bearer " + OTHER_TOKEN))
			.andExpect(status().isForbidden());
	}

	@Test
	void bearerPutWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(put("/bosk/state/targets/plain")
				.header("Authorization", "Bearer " + OTHER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"id\":\"plain\",\"name\":\"updated\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void bearerDeleteWithoutBearerToken_isUnauthorized() throws Exception {
		mockMvc.perform(delete("/bosk/state/targets/plain"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void bearerDeleteWithoutAuthority_isForbidden() throws Exception {
		mockMvc.perform(delete("/bosk/state/targets/plain")
				.header("Authorization", "Bearer " + OTHER_TOKEN))
			.andExpect(status().isForbidden());
	}
}
