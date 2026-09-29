package works.bosk.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Requires that the current authentication carries a particular authority.
 * <p>
 * Because the check runs in the endpoint rather than in a filter chain, it can be reached by a
 * request that Spring Security never processed, for example when no chain matches the maintenance
 * path. Such a request has no authentication and no entry point to challenge the caller, so it is
 * denied directly; otherwise the usual {@link AccessDeniedException} lets Spring Security's
 * {@code ExceptionTranslationFilter} produce the challenge or 403.
 */
class AuthorityMaintenanceAuthorization implements MaintenanceAuthorization {
	private final String authority;

	AuthorityMaintenanceAuthorization(String authority) {
		this.authority = authority;
	}

	@Override
	public void check(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, denialMessage());
		} else if (!hasAuthority(authentication)) {
			throw new AccessDeniedException(denialMessage());
		}
	}

	final boolean hasAuthority(Authentication authentication) {
		for (GrantedAuthority granted : authentication.getAuthorities()) {
			if (authority.equals(granted.getAuthority())) {
				return true;
			}
		}
		return false;
	}

	final String denialMessage() {
		return "Access to the bosk maintenance endpoints requires authority \"" + authority + "\"";
	}
}
