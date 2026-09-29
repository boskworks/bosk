package works.bosk.spring.boot;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides whether the current request may access the {@link MaintenanceEndpoints}.
 * <p>
 * Implementations are package-private and are chosen by
 * {@link BoskMaintenanceAutoConfiguration} according to the configured
 * {@link MaintenanceAccess}.
 */
interface MaintenanceAuthorization {
	/**
	 * @param request the request being authorized
	 * @throws org.springframework.security.access.AccessDeniedException if the caller is
	 * authenticated but not permitted, so that Spring Security's
	 * {@code ExceptionTranslationFilter} produces the usual challenge or 403
	 * @throws org.springframework.web.server.ResponseStatusException if the request did not pass
	 * through Spring Security at all, so there is no entry point to challenge the caller
	 */
	void check(HttpServletRequest request);
}
