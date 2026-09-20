import works.bosk.spring.boot.ReadSessionFilter;

/**
 * Spring Boot integration for Bosk applications.
 * <p>
 * Features include:
 * <ul>
 *   <li>
 *     <b>Automatic read session</b> —
 *     {@link ReadSessionFilter ReadSessionFilter}
 *     opens a read session automatically for every HTTP {@code GET}, {@code HEAD}, and {@code OPTIONS}
 *     request. In many cases this means your application never needs to open its own sessions,
 *     except for {@code POST} operations that behave like a {@code GET} with a body, or for background
 *     operations executed on a separate thread.
 *     <p>
 *     This feature is enabled by default and can be disabled with the Spring property
 *     {@code bosk.web-api.read-session} set to {@code false}.
 *     If you need finer control over sessions, consider using
 *     {@link works.bosk.Bosk#supersedingReadSession() Bosk.supersedingReadSession()} rather than disabling
 *     automatic read sessions globally.
 *   </li>
 *   <li>
 *     <b>Maintenance endpoints</b> — The
 *     {@link works.bosk.spring.boot.MaintenanceEndpoints MaintenanceEndpoints} component registers HTTP
 *     endpoints providing direct {@code GET}, {@code PUT}, and {@code DELETE} access to Bosk state in JSON.
 *     Endpoints are prefixed by the value of the {@code bosk.web-api.maintenance.path} setting (default
 *     {@code /bosk/state}), followed by the path of the node within the Bosk state. They are intended for
 *     troubleshooting, manual operations, or integration with external systems that need full control over
 *     the Bosk state.
 *     <p>
 *     Because these endpoints expose full read and write access to the state tree, they are disabled unless
 *     {@code bosk.web-api.maintenance.access} explicitly selects one of:
 *     <ul>
 *       <li>{@code UNSECURED} — access requires no authorization. This mode is for local development
 *       without Spring Security, and it is an error to select it when Spring Security is present.</li>
 *       <li>{@code BEARER} — access requires the authority named by
 *       {@code bosk.web-api.maintenance.authority} (default {@code bosk:state}) and a bearer token in
 *       the {@code Authorization} header. Other kinds of authentication, such as a browser session, are
 *       refused. Because a browser does not attach a bearer token automatically, CSRF protection is not
 *       applied to the endpoints in this mode. It is intended for {@code curl}, the IntelliJ HTTP client,
 *       and service-to-service callers, and it requires the application to have a bearer-token
 *       authentication mechanism configured.</li>
 *       <li>{@code AUTHENTICATED} — access requires the authority, using whatever authentication the
 *       application has configured. Spring Security's CSRF protection applies, so clients that issue
 *       {@code PUT} or {@code DELETE} must supply a CSRF token (an anti-forgery value, not an
 *       authentication credential); this generally means a browser client, or a machine client for which
 *       the application has exposed a token (for example using a {@code CookieCsrfTokenRepository}).
 *       Applications whose clients are machines should prefer {@code BEARER}.</li>
 *       <li>{@code NONE} — the endpoints are not registered. This is the default.</li>
 *     </ul>
 *     {@code BEARER} and {@code AUTHENTICATED} require Spring Security, and it is an error to select them
 *     without it on the classpath.
 *     <p>
 *     Both {@code BEARER} and {@code AUTHENTICATED} are secure. {@code AUTHENTICATED} accepts more forms
 *     of authentication, but clients that issue {@code PUT} or {@code DELETE} must supply a CSRF token.
 *     {@code BEARER} accepts only bearer tokens, but {@code PUT} and {@code DELETE} work without a CSRF
 *     token, which is more convenient for machine clients such as {@code curl} and the IntelliJ HTTP
 *     client.
 *     <p>
 *     The authorization check runs in the endpoints themselves and throws {@code AccessDeniedException},
 *     so Spring Security produces the usual {@code 401} or {@code 403} responses. The
 *     {@link works.bosk.spring.boot.BoskMaintenanceRequestMatcher BoskMaintenanceRequestMatcher} bean
 *     matches the endpoints for applications that prefer to write declarative rules in their own
 *     {@code SecurityFilterChain}; such rules are additive to the check in the endpoints.
 *     <p>
 *     These endpoints support ETags via the {@code If-Match} and {@code If-None-Match} headers, exposing a
 *     limited ability to do conditional updates. Nodes participating in this feature must have a field named
 *     {@code revision} of type {@link works.bosk.Identifier Identifier}. Using the {@code If-*} headers on such
 *     a node has the following effects:
 *     <ul>
 *       <li>{@code If-None-Match: *} — if the node already exists, no action is taken.</li>
 *       <li>{@code If-Match: {ID}} — if the node does not exist, or its {@code revision} field has a different
 *       value, no action is taken.</li>
 *     </ul>
 *     Spring Security's default {@code StrictHttpFirewall} rejects URLs containing percent-encoded slashes
 *     or percent signs, so identifiers containing those characters are unreachable through the endpoints
 *     while Spring Security is present unless the application customizes its {@code HttpFirewall}.
 *   </li>
 *   <li>
 *     <b>MongoDB-backed bosks</b> — When the {@code bosk-mongo} library is on the
 *     classpath, the {@code bosk.mongodb.*} properties configure the beans needed to
 *     build a MongoDB-backed bosk, reusing the application's existing
 *     {@code spring.mongodb.*} connection and database when present.
 *   </li>
 * </ul>
 */
module works.bosk.spring.boot {
	requires transitive tools.jackson.databind;
	requires transitive org.apache.tomcat.embed.core;
	requires org.slf4j;
	requires spring.boot.autoconfigure;
	requires transitive spring.boot;
	requires transitive spring.context;
	requires transitive spring.web;
	requires transitive works.bosk.core;
	requires transitive works.bosk.jackson;

	// Support MongoDB if it's present, but don't require it
	requires static works.bosk.mongo;
	requires static spring.boot.mongodb;

	// Support Spring Security if it's present, but don't require it
	requires static spring.security.config;
	requires static spring.security.core;
	requires static spring.security.web;

	requires static lombok;

	exports works.bosk.spring.boot;
}
