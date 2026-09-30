package works.bosk.spring.boot;

/**
 * Controls whether the {@link MaintenanceEndpoints maintenance endpoints} are registered,
 * and how access to them is controlled.
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
	 * The maintenance endpoints are registered with no authorization of their own. This mode is
	 * for local development, and it is an error to select it when Spring Security is present.
	 */
	UNSECURED,

	/**
	 * The maintenance endpoints are registered and must be authenticated by the application.
	 * It is an error to select this mode when Spring Security is not present, and the application
	 * fails to start if an unauthenticated request could reach the endpoints.
	 * <p>
	 * The application decides its own authorization policy. For example:
	 * <pre>{@code
	 * @Bean
	 * SecurityFilterChain maintenanceEndpoints(HttpSecurity http,
	 *         @Value("${bosk.web.maintenance.path:/bosk/state}") String maintenancePath) throws Exception {
	 *     http.authorizeHttpRequests(auth -> auth
	 *         .requestMatchers(maintenancePath + "/**").hasAuthority("bosk:state"));
	 *     return http.build();
	 * }
	 * }</pre>
	 */
	AUTHENTICATED
}
