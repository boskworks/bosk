package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.StateTreeNode;
import works.bosk.jackson.JacksonSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the startup check that {@link MaintenanceAccess#AUTHENTICATED} mode performs: the
 * application must have a filter chain that denies unauthenticated access to the maintenance path.
 */
class MaintenanceEndpointAuthenticationTest {
	public record Target(Identifier id, String name) implements Entity {}

	public record State(Catalog<Target> targets) implements StateTreeNode {}

	// Excludes Boot's generated default user, which these tests don't use.
	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
	static class TestConfig {
		@Bean
		Bosk<State> bosk() {
			return new Bosk<>(
				MaintenanceEndpointAuthenticationTest.class.getSimpleName(),
				State.class,
				_ -> new State(Catalog.of()),
				BoskConfig.simple()
			);
		}

		@Bean
		JacksonSerializer jacksonSerializer() {
			return new JacksonSerializer();
		}

		@Bean
		ObjectMapper objectMapper(Bosk<State> bosk, JacksonSerializer jacksonSerializer) {
			return JsonMapper.builder()
				.addModule(jacksonSerializer.moduleFor(bosk))
				.build();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class ProtectedChain {
		@Bean
		SecurityFilterChain chain(
			HttpSecurity http,
			@Value("${bosk.web.maintenance.path:/bosk/state}") String maintenancePath
		) throws Exception {
			http.authorizeHttpRequests(auth -> auth
				.requestMatchers(maintenancePath + "/**").hasAuthority("bosk:state")
				.anyRequest().authenticated());
			return http.build();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class PermitAllChain {
		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class UnrelatedChain {
		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			http
				.securityMatcher("/other/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
			return http.build();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class GetOnlyChain {
		@Bean
		SecurityFilterChain chain(
			HttpSecurity http,
			@Value("${bosk.web.maintenance.path:/bosk/state}") String maintenancePath
		) throws Exception {
			http.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, maintenancePath + "/**").hasAuthority("bosk:state")
				.anyRequest().permitAll());
			return http.build();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class BasePathOnlyChain {
		@Bean
		SecurityFilterChain chain(
			HttpSecurity http,
			@Value("${bosk.web.maintenance.path:/bosk/state}") String maintenancePath
		) throws Exception {
			http.authorizeHttpRequests(auth -> auth
				.requestMatchers(maintenancePath).hasAuthority("bosk:state")
				.anyRequest().permitAll());
			return http.build();
		}
	}

	private WebApplicationContextRunner runnerWith(Class<?> chainConfiguration) {
		return new WebApplicationContextRunner()
			.withUserConfiguration(TestConfig.class, chainConfiguration)
			.withPropertyValues("bosk.web.maintenance.access=AUTHENTICATED");
	}

	@Test
	void protectedMaintenancePath_starts() {
		runnerWith(ProtectedChain.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(MaintenanceEndpoints.class);
		});
	}

	@Test
	void permittedMaintenancePath_failsToStart() {
		runnerWith(PermitAllChain.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("an unauthenticated GET request");
		});
	}

	@Test
	void unmatchedMaintenancePath_failsToStart() {
		runnerWith(UnrelatedChain.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("no SecurityFilterChain matches a GET request");
		});
	}

	@Test
	void pathProtectedOnlyForGet_failsToStart() {
		runnerWith(GetOnlyChain.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("an unauthenticated PUT request");
		});
	}

	@Test
	void basePathProtectedButNotDescendants_failsToStart() {
		runnerWith(BasePathOnlyChain.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("an unauthenticated GET request");
		});
	}
}
