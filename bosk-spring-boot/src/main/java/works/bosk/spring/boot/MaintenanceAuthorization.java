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
	 * @throws org.springframework.security.access.AccessDeniedException if access is denied
	 */
	void check(HttpServletRequest request);
}
