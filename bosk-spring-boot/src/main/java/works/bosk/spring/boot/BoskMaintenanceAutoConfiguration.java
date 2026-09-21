package works.bosk.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;
import works.bosk.Bosk;
import works.bosk.jackson.JacksonSerializer;

/**
 * Auto-configures the {@link MaintenanceEndpoints} and the authorization that protects them.
 * <p>
 * The {@link MaintenanceAccess} is explicit: the endpoints expose full read and write access to
 * the bosk state tree, so they are never registered unless an access is selected. The access and
 * the availability of Spring Security must agree; a contradiction fails application startup
 * rather than silently relaxing or disabling the authorization.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebApiProperties.class)
@SuppressWarnings("exports") // because this public API is annotated with types from the non-transitive spring.boot.autoconfigure module
public class BoskMaintenanceAutoConfiguration {

	@Bean
	@Conditional(MaintenanceEnabledCondition.class)
	MaintenanceEndpoints maintenanceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonSerializer jackson,
		MaintenanceAuthorization authorization
	) {
		return new MaintenanceEndpoints(bosk, mapper, jackson, authorization);
	}

	@Bean
	MaintenancePropertiesValidator boskMaintenancePropertiesValidator(Environment environment) {
		return new MaintenancePropertiesValidator(environment);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "AUTHENTICATED")
	@ConditionalOnClass(RequestMatcher.class)
	static class AuthenticatedMaintenanceConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization(WebApiProperties properties) {
			return new AuthorityMaintenanceAuthorization(properties.maintenance().authority());
		}

		@Bean
		BoskMaintenanceRequestMatcher boskMaintenanceRequestMatcher(WebApiProperties properties) {
			return new BoskMaintenanceRequestMatcher(properties.maintenance().path());
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "AUTHENTICATED")
	@ConditionalOnMissingClass("org.springframework.security.web.util.matcher.RequestMatcher")
	static class AuthenticatedWithoutSecurityConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			throw new IllegalStateException(
				"bosk.web-api.maintenance.access=AUTHENTICATED requires Spring Security on the classpath. "
					+ "Add a Spring Security dependency, or set bosk.web-api.maintenance.access=UNSECURED for "
					+ "local development without Spring Security.");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "BEARER")
	@ConditionalOnClass({RequestMatcher.class, HttpSecurity.class})
	static class BearerMaintenanceConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization(WebApiProperties properties) {
			return new BearerMaintenanceAuthorization(properties.maintenance().authority());
		}

		@Bean
		BoskMaintenanceRequestMatcher boskMaintenanceRequestMatcher(WebApiProperties properties) {
			return new BoskMaintenanceRequestMatcher(properties.maintenance().path());
		}

		/**
		 * A browser does not attach a bearer token automatically, so the maintenance endpoints
		 * do not need CSRF protection in this mode. This customizer applies to every security
		 * filter chain, including Boot's default one, and only exempts the maintenance path;
		 * an application that configures CSRF itself can override it.
		 */
		@Bean
		Customizer<HttpSecurity> boskMaintenanceCsrfCustomizer(BoskMaintenanceRequestMatcher matcher) {
			return http -> http.csrf(csrf -> csrf.ignoringRequestMatchers(matcher));
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "BEARER")
	@ConditionalOnMissingClass("org.springframework.security.web.util.matcher.RequestMatcher")
	static class BearerWithoutSecurityConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			throw new IllegalStateException(
				"bosk.web-api.maintenance.access=BEARER requires Spring Security on the classpath. "
					+ "Add a Spring Security dependency, or set bosk.web-api.maintenance.access=UNSECURED for "
					+ "local development without Spring Security.");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "BEARER")
	@ConditionalOnClass(RequestMatcher.class)
	@ConditionalOnMissingClass("org.springframework.security.config.annotation.web.builders.HttpSecurity")
	static class BearerWithoutConfigConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			throw new IllegalStateException(
				"bosk.web-api.maintenance.access=BEARER requires Spring Security's config module, because it "
					+ "exempts the maintenance path from CSRF protection. Add it, or set "
					+ "bosk.web-api.maintenance.access=UNSECURED for local development without Spring Security.");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "UNSECURED")
	@ConditionalOnClass(RequestMatcher.class)
	static class UnsecuredWithSecurityConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			throw new IllegalStateException(
				"bosk.web-api.maintenance.access=UNSECURED is not permitted because Spring Security is present. "
					+ "Set bosk.web-api.maintenance.access to BEARER or AUTHENTICATED and grant the configured authority.");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "UNSECURED")
	@ConditionalOnMissingClass("org.springframework.security.web.util.matcher.RequestMatcher")
	static class UnsecuredMaintenanceConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			return PermitAllMaintenanceAuthorization.INSTANCE;
		}
	}

	/**
	 * Matches when the maintenance endpoints are enabled, that is, when the access is anything
	 * other than {@link MaintenanceAccess#NONE}. An unrecognized access is treated as enabled
	 * here so that property binding reports the problem.
	 */
	static class MaintenanceEnabledCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String access = context.getEnvironment().getProperty("bosk.web-api.maintenance.access", "NONE");
			return !MaintenanceAccess.NONE.name().equalsIgnoreCase(access);
		}
	}
}
