package works.bosk.spring.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
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
class BoskMaintenanceAutoConfiguration {

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

	/**
	 * Warns when maintenance settings are set but no access is selected, so an unregistered set of
	 * endpoints is not a silent surprise.
	 */
	@Bean
	InitializingBean boskMaintenanceSettingsWarning(Environment environment) {
		return () -> {
			String access = environment.getProperty("bosk.web-api.maintenance.access", MaintenanceAccess.NONE.name());
			boolean endpointsDisabled = MaintenanceAccess.NONE.name().equalsIgnoreCase(access);
			boolean settingsPresent = environment.containsProperty("bosk.web-api.maintenance.path")
				|| environment.containsProperty("bosk.web-api.maintenance.authority");
			if (endpointsDisabled && settingsPresent) {
				LOGGER.warn(
					"bosk.web-api.maintenance.path or bosk.web-api.maintenance.authority is set, but "
						+ "bosk.web-api.maintenance.access is NONE, so the maintenance endpoints will not be "
						+ "registered. Set bosk.web-api.maintenance.access to UNSECURED or AUTHENTICATED to enable them.");
			}
		};
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
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "UNSECURED")
	@ConditionalOnClass(RequestMatcher.class)
	static class UnsecuredWithSecurityConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			throw new IllegalStateException(
				"bosk.web-api.maintenance.access=UNSECURED is not permitted because Spring Security is present. "
					+ "Set bosk.web-api.maintenance.access to AUTHENTICATED and grant the configured authority.");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web-api.maintenance", name = "access", havingValue = "UNSECURED")
	@ConditionalOnMissingClass("org.springframework.security.web.util.matcher.RequestMatcher")
	static class UnsecuredMaintenanceConfiguration {
		@Bean
		MaintenanceAuthorization maintenanceAuthorization() {
			return request -> { };
		}
	}

	/**
	 * Matches when the maintenance endpoints are enabled, that is, when the access is one of the
	 * recognized values other than {@link MaintenanceAccess#NONE}. An unrecognized value does not
	 * match, so binding {@link WebApiProperties} reports it with a clear error rather than the
	 * endpoints failing later for want of an authorization bean.
	 */
	static class MaintenanceEnabledCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String access = context.getEnvironment().getProperty("bosk.web-api.maintenance.access");
			return MaintenanceAccess.UNSECURED.name().equalsIgnoreCase(access)
				|| MaintenanceAccess.AUTHENTICATED.name().equalsIgnoreCase(access);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BoskMaintenanceAutoConfiguration.class);
}
