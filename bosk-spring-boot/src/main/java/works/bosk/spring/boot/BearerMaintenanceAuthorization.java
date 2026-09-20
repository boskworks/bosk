package works.bosk.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Requires the configured authority and a bearer token in the {@code Authorization} header.
 * <p>
 * This is used by {@link MaintenanceAccess#BEARER}. A browser does not attach a bearer token
 * automatically, so a request that carries one cannot have been forged by a cross-site
 * request, and the maintenance endpoints do not need CSRF protection.
 */
final class BearerMaintenanceAuthorization extends AuthorityMaintenanceAuthorization {
	private static final String BEARER_PREFIX = "Bearer ";

	BearerMaintenanceAuthorization(String authority) {
		super(authority);
	}

	@Override
	public void check(HttpServletRequest request) {
		super.check(request);
		if (!hasBearerToken(request)) {
			throw new AccessDeniedException(
				"The bosk maintenance endpoints require a bearer token in the Authorization header");
		}
	}

	private boolean hasBearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		return header != null
			&& header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
	}
}
