package works.bosk.spring.boot;

/**
 * Controls whether the {@link MaintenanceEndpoints maintenance endpoints} are registered,
 * and how access to them is authorized.
 * <p>
 * The access is deliberately explicit: the maintenance endpoints expose full read and write
 * access to the bosk state tree, so they are never enabled implicitly.
 */
public enum MaintenanceAccess {
	/**
	 * The maintenance endpoints are not registered. This is the default.
	 */
	NONE,

	/**
	 * The maintenance endpoints are registered and are accessible without any Spring Security
	 * authorization check. This mode exists for local development, where a developer wants to
	 * inspect and edit the state tree without configuring Spring Security; it is a configuration
	 * error to select it when Spring Security is present.
	 * <p>
	 * Selecting this mode is an explicit acknowledgement that the endpoints are unsecured.
	 */
	UNSECURED,

	/**
	 * The maintenance endpoints are registered and require the configured
	 * {@link WebApiProperties.Maintenance#authority() authority}, using whatever authentication
	 * the application has configured. Spring Security's CSRF protection applies as usual, so
	 * clients that issue {@code PUT} or {@code DELETE} must supply a CSRF token.
	 * <p>
	 * It is a configuration error to select this mode when Spring Security is not present.
	 */
	AUTHENTICATED
}
