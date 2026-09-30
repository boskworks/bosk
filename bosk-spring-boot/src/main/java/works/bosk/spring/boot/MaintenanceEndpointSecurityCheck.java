package works.bosk.spring.boot;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Verifies at startup that the application requires authentication for the maintenance endpoints.
 * <p>
 * The endpoints do not enforce authorization themselves; keeping them closed is the application's
 * filter chain's job. Because a missing rule would silently leave them open, we inspect the
 * configured chain here and refuse to start if an unauthenticated request could reach any of them.
 * <p>
 * The endpoints answer every HTTP method at both the maintenance path and its descendants, so we
 * probe each of those combinations, not just a single request.
 */
final class MaintenanceEndpointSecurityCheck implements InitializingBean {
	private final FilterChainProxy filterChainProxy;
	private final WebProperties properties;

	MaintenanceEndpointSecurityCheck(FilterChainProxy filterChainProxy, WebProperties properties) {
		this.filterChainProxy = filterChainProxy;
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		String path = normalizedPath();
		for (String method : PROBE_METHODS) {
			for (String uri : List.of(path, descendant(path))) {
				checkDeniesAnonymous(path, method, uri);
			}
		}
	}

	private void checkDeniesAnonymous(String maintenancePath, String method, String uri) {
		List<Filter> filters = filterChainProxy.getFilters(uri);
		if (filters == null || filters.isEmpty()) {
			throw failure(maintenancePath, "no SecurityFilterChain matches a " + method + " request to \"" + uri + "\"");
		}
		AuthorizationFilter authorization = filters.stream()
			.filter(AuthorizationFilter.class::isInstance)
			.map(AuthorizationFilter.class::cast)
			.findFirst()
			.orElseThrow(() -> failure(maintenancePath, "the chain matching \"" + uri + "\" does not authorize requests"));
		HttpServletRequest probe = attributesSupporting(new FilterInvocation(uri, method).getRequest());
		AuthorizationResult result = authorization.getAuthorizationManager().authorize(() -> null, probe);
		if (result.isGranted()) {
			throw failure(maintenancePath, "an unauthenticated " + method + " request to \"" + uri + "\" would be allowed");
		}
	}

	/**
	 * {@link FilterInvocation}'s synthetic request does not support attributes, which some
	 * authorization components set; wrap it so the probe behaves like a real request.
	 */
	private static HttpServletRequest attributesSupporting(HttpServletRequest request) {
		return new HttpServletRequestWrapper(request) {
			private final Map<String, Object> attributes = new HashMap<>();

			@Override
			public void setAttribute(String name, Object value) {
				attributes.put(name, value);
			}

			@Override
			public Object getAttribute(String name) {
				return attributes.get(name);
			}

			@Override
			public void removeAttribute(String name) {
				attributes.remove(name);
			}

			@Override
			public Enumeration<String> getAttributeNames() {
				return Collections.enumeration(attributes.keySet());
			}
		};
	}

	private String normalizedPath() {
		String path = properties.maintenance().path();
		return path.startsWith("/") ? path : "/" + path;
	}

	private static String descendant(String path) {
		return path.endsWith("/") ? path + "boskProbe" : path + "/boskProbe";
	}

	private static IllegalStateException failure(String maintenancePath, String problem) {
		return new IllegalStateException(
			"bosk.web.maintenance.access=AUTHENTICATED, but the maintenance endpoints at \""
				+ maintenancePath + "\" are not protected: " + problem + ". Add a rule such as "
				+ "requestMatchers(\"" + maintenancePath + "/**\").hasAuthority(\"bosk:state\") to your "
				+ "SecurityFilterChain, or set bosk.web.maintenance.access to UNSECURED if the endpoints "
				+ "should be open.");
	}

	private static final List<String> PROBE_METHODS = List.of("GET", "PUT", "DELETE");
}
