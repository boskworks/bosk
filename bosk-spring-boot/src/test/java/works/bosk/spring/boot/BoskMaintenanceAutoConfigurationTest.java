package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.util.matcher.RequestMatcher;
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

class BoskMaintenanceAutoConfigurationTest {
	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(BoskMaintenanceAutoConfiguration.class))
		.withUserConfiguration(TestDependencies.class);

	public record Target(Identifier id, String name) implements Entity {}

	public record State(Catalog<Target> targets) implements StateTreeNode {}

	@Configuration(proxyBeanMethods = false)
	static class TestDependencies {
		@Bean
		Bosk<State> bosk() {
			return new Bosk<>(
				BoskMaintenanceAutoConfigurationTest.class.getSimpleName(),
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
		ObjectMapper objectMapper() {
			return JsonMapper.builder().build();
		}
	}

	@Test
	void defaultMode_isDisabled() {
		runner.run(context -> assertThat(context).doesNotHaveBean(MaintenanceEndpoints.class));
	}

	@Test
	void disabledMode_doesNotRegisterEndpoints() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=NONE")
			.run(context -> assertThat(context).doesNotHaveBean(MaintenanceEndpoints.class));
	}

	@Test
	void unsecuredWithoutSecurity_registersEndpoints() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=UNSECURED")
			.withClassLoader(new FilteredClassLoader(RequestMatcher.class))
			.run(context -> {
				assertThat(context).hasSingleBean(MaintenanceEndpoints.class);
				assertThat(context.getBean(MaintenanceAuthorization.class))
					.isInstanceOf(PermitAllMaintenanceAuthorization.class);
			});
	}

	@Test
	void unsecuredWithSecurity_failsFast() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=UNSECURED")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining("is not permitted because Spring Security is present");
			});
	}

	@Test
	void authenticatedWithSecurity_registersEndpointsAndMatcher() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=AUTHENTICATED")
			.run(context -> {
				assertThat(context).hasSingleBean(MaintenanceEndpoints.class);
				assertThat(context).hasSingleBean(BoskMaintenanceRequestMatcher.class);
				assertThat(context.getBean(MaintenanceAuthorization.class))
					.isInstanceOf(AuthorityMaintenanceAuthorization.class);
			});
	}

	@Test
	void authenticatedWithoutSecurity_failsFast() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=AUTHENTICATED")
			.withClassLoader(new FilteredClassLoader(RequestMatcher.class))
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining("requires Spring Security");
			});
	}

	@Test
	void bearerWithSecurity_registersEndpointsMatcherAndCsrfCustomizer() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=BEARER")
			.run(context -> {
				assertThat(context).hasSingleBean(MaintenanceEndpoints.class);
				assertThat(context).hasSingleBean(BoskMaintenanceRequestMatcher.class);
				assertThat(context).hasBean("boskMaintenanceCsrfCustomizer");
				assertThat(context.getBean(MaintenanceAuthorization.class))
					.isInstanceOf(BearerMaintenanceAuthorization.class);
			});
	}

	@Test
	void bearerWithoutSecurity_failsFast() {
		runner.withPropertyValues("bosk.web-api.maintenance.access=BEARER")
			.withClassLoader(new FilteredClassLoader(RequestMatcher.class))
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining("requires Spring Security");
			});
	}

	@Test
	void bearerWithoutConfig_failsFast() {
		// spring-security-web does not depend on spring-security-config, so BEARER must
		// require both: the matcher needs web, and the CSRF exemption needs config.
		runner.withPropertyValues("bosk.web-api.maintenance.access=BEARER")
			.withClassLoader(new FilteredClassLoader(HttpSecurity.class))
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining("requires Spring Security's config module");
			});
	}
}
