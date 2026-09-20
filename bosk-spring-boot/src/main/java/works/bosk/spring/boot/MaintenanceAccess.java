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
	 * clients that issue {@code PUT} or {@code DELETE} must supply a CSRF token; applications
	 * whose clients are machines should prefer {@link #BEARER}.
	 * <p>
	 * It is a configuration error to select this mode when Spring Security is not present.
	 */
	AUTHENTICATED,

	/**
	 * The maintenance endpoints are registered and require the configured
	 * {@link WebApiProperties.Maintenance#authority() authority} as well as a bearer token in the
	 * {@code Authorization} header. Other kinds of authentication, such as a browser session,
	 * are not accepted.
	 * <p>
	 * Because a browser does not attach a bearer token automatically, this mode does not need
	 * CSRF protection, and none is applied to the maintenance endpoints. It is intended for
	 * clients such as {@code curl}, the IntelliJ HTTP client, and service-to-service callers,
	 * and it requires the application to have a bearer-token authentication mechanism
	 * configured.
	 * <p>
	 * It is a configuration error to select this mode when Spring Security is not present.
	 */
	BEARER
}
