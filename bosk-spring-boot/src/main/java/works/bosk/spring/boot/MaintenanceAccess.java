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
	 * the application has configured. It is a configuration error to select this mode when
	 * Spring Security is not present.
	 * <p>
	 * A request must be authenticated by Spring Security and carry the {@code bosk:state}
	 * authority, which the application grants to the users and clients that should reach the
	 * endpoints. The endpoints enforce this themselves, so no application rule is needed.
	 * <p>
	 * {@code PUT} and {@code DELETE} require a CSRF token, so keep Spring Security's CSRF
	 * protection on for the path and exempt it only when the endpoint cannot be reached with
	 * ambient credentials; if in doubt, keep it on and give clients a token. See Spring Security's
	 * <a href="https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html">CSRF
	 * reference</a> for details.
	 */
	AUTHENTICATED
}
