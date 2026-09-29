package works.bosk.spring.boot;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Allows every request. Used only when {@link MaintenanceAccess#UNSECURED} has been explicitly
 * selected and Spring Security is absent.
 */
final class PermitAllMaintenanceAuthorization implements MaintenanceAuthorization {
	static final PermitAllMaintenanceAuthorization INSTANCE = new PermitAllMaintenanceAuthorization();

	private PermitAllMaintenanceAuthorization() {
	}

	@Override
	public void check(HttpServletRequest request) {
	}
}
