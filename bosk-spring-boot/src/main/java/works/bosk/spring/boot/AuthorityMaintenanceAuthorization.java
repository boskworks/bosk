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
 * When the request was processed by Spring Security, throwing {@link AccessDeniedException}
 * lets {@code ExceptionTranslationFilter} produce the usual responses: anonymous callers are
 * sent to the configured authentication entry point, and authenticated callers without the
 * authority receive a 403. If there is no authentication at all, the request did not pass
 * through Spring Security (for example, the maintenance path was ignored), so there is no
 * entry point to challenge the caller and we deny directly.
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
