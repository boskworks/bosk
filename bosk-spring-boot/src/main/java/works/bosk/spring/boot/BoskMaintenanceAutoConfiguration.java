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
import org.springframework.security.web.FilterChainProxy;
import tools.jackson.databind.ObjectMapper;
import works.bosk.Bosk;
import works.bosk.jackson.JacksonSerializer;

import static works.bosk.spring.boot.MaintenanceAccess.AUTHENTICATED;
import static works.bosk.spring.boot.MaintenanceAccess.NONE;
import static works.bosk.spring.boot.MaintenanceAccess.UNSECURED;

/**
 * Auto-configures the {@link MaintenanceEndpoints} and, in {@link MaintenanceAccess#AUTHENTICATED}
 * mode, verifies that the application authenticates them.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebProperties.class)
class BoskMaintenanceAutoConfiguration {

	@Bean
	@Conditional(MaintenanceEnabledCondition.class)
	MaintenanceEndpoints maintenanceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonSerializer jackson
	) {
		return new MaintenanceEndpoints(bosk, mapper, jackson);
	}

	/**
	 * Warns when maintenance settings are set but no access is selected, so an unregistered set of
	 * endpoints is not a silent surprise.
	 */
	@Bean
	InitializingBean boskMaintenanceSettingsWarning(Environment environment) {
		return () -> {
			String access = environment.getProperty("bosk.web.maintenance.access", NONE.name());
			boolean endpointsDisabled = NONE.name().equalsIgnoreCase(access);
			boolean settingsPresent = environment.containsProperty("bosk.web.maintenance.path");
			if (endpointsDisabled && settingsPresent) {
				LOGGER.warn(
					"bosk.web.maintenance.path is set, but bosk.web.maintenance.access is NONE, so the "
						+ "maintenance endpoints will not be registered. Set bosk.web.maintenance.access to "
						+ "UNSECURED or AUTHENTICATED to enable them.");
			}
		};
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web.maintenance", name = "access", havingValue = "AUTHENTICATED")
	@ConditionalOnClass(FilterChainProxy.class)
	static class AuthenticatedMaintenanceConfiguration {
		@Bean
		InitializingBean maintenanceAuthenticationCheck(FilterChainProxy filterChainProxy, WebProperties properties) {
			return new MaintenanceEndpointSecurityCheck(filterChainProxy, properties);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web.maintenance", name = "access", havingValue = "AUTHENTICATED")
	@ConditionalOnMissingClass("org.springframework.security.web.FilterChainProxy")
	static class AuthenticatedWithoutSecurityConfiguration {
		@Bean
		InitializingBean maintenanceAuthenticationCheck() {
			throw new IllegalStateException(
				"bosk.web.maintenance.access=AUTHENTICATED requires Spring Security on the classpath. "
					+ "Add a Spring Security dependency, or set bosk.web.maintenance.access=UNSECURED for "
					+ "local development without Spring Security.");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "bosk.web.maintenance", name = "access", havingValue = "UNSECURED")
	@ConditionalOnClass(FilterChainProxy.class)
	static class UnsecuredWithSecurityConfiguration {
		@Bean
		InitializingBean maintenanceEndpointsRequireNoSecurity() {
			throw new IllegalStateException(
				"bosk.web.maintenance.access=UNSECURED is not permitted because Spring Security is present. "
					+ "Set bosk.web.maintenance.access to AUTHENTICATED and authenticate the maintenance endpoints.");
		}
	}

	/**
	 * Matches when the maintenance endpoints are enabled, that is, when the access is one of the
	 * recognized values other than {@link MaintenanceAccess#NONE}. An unrecognized value does not
	 * match, so binding {@link WebProperties} reports it with a clear error rather than the
	 * endpoints failing later for want of an initialization bean.
	 */
	static class MaintenanceEnabledCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String access = context.getEnvironment().getProperty("bosk.web.maintenance.access");
			return UNSECURED.name().equalsIgnoreCase(access)
				|| AUTHENTICATED.name().equalsIgnoreCase(access);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BoskMaintenanceAutoConfiguration.class);
}
