package works.bosk.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches the maintenance endpoints: the configured maintenance path and everything beneath it.
 * <p>
 * This is exposed as a bean so that applications can reference the maintenance endpoints in
 * their own Spring Security configuration. It is a coarse, URL-based matcher; authorization
 * decisions based on the structure of the bosk state tree should not be built on it.
 */
@SuppressWarnings("exports") // because this public API references the optional Spring Security module
public final class BoskMaintenanceRequestMatcher implements RequestMatcher {
	private final RequestMatcher delegate;

	public BoskMaintenanceRequestMatcher(String path) {
		this.delegate = PathPatternRequestMatcher.withDefaults().matcher(patternFor(path));
	}

	@Override
	public boolean matches(HttpServletRequest request) {
		return delegate.matches(request);
	}

	@Override
	public String toString() {
		return delegate.toString();
	}

	/**
	 * The maintenance path with a leading slash, no trailing slash (except for the root),
	 * and a {@code /**} suffix that also matches the base path itself.
	 */
	static String patternFor(String path) {
		String normalized = path == null || path.isBlank() ? WebApiProperties.DEFAULT_MAINTENANCE_PATH : path;
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		while (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.equals("/") ? "/**" : normalized + "/**";
	}
}
