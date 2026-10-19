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
 *     {@code bosk.web.read-session} set to {@code false}.
 *     If you need finer control over sessions, consider using
 *     {@link works.bosk.Bosk#supersedingReadSession() Bosk.supersedingReadSession()} rather than disabling
 *     automatic read sessions globally.
 *   </li>
 *   <li>
 *     <b>Maintenance endpoints</b> — The
 *     {@link works.bosk.spring.boot.MaintenanceEndpoints MaintenanceEndpoints} component registers HTTP
 *     endpoints providing direct {@code GET}, {@code PUT}, and {@code DELETE} access to Bosk state in JSON.
 *     Endpoints are prefixed by the value of the {@code bosk.web.maintenance.path} setting (default
 *     {@code /bosk/state}), followed by the path of the node within the Bosk state. They are intended for
 *     troubleshooting, manual operations, or integration with external systems that need full control over
 *     the Bosk state.
 *     <p>
 *     Because these endpoints expose full read and write access to the state tree, they are disabled unless
 *     {@code bosk.web.maintenance.access} explicitly selects a
 *     {@link works.bosk.spring.boot.MaintenanceAccess MaintenanceAccess}:
 *     <ul>
 *       <li>{@code UNSECURED} — the endpoints are registered with no authorization of their own. This mode is
 *       for local development, and it is an error to select it when Spring Security is present.</li>
 *       <li>{@code AUTHENTICATED} — the endpoints are registered and must be authenticated by the
 *       application. It is an error to select this mode when Spring Security is not present, and the
 *       application fails to start if an unauthenticated request could reach the endpoints. The application
 *       chooses its own policy; for example,
 *       {@code requestMatchers(path + "/**").hasAuthority("bosk:state")}.</li>
 *       <li>{@code NONE} — the endpoints are not registered. This is the default.</li>
 *     </ul>
 *     <p>
 *     A {@code GET} response for a node with a field named {@code revision} of type
 *     {@link works.bosk.Identifier Identifier} includes a strong {@code ETag} whose value is that
 *     revision. The ETag is quoted and percent-encoded using the same scheme as path
 *     segments, so any revision string is supported and the result is always a valid
 *     strong ETag. Nodes without such a field, {@code 404} responses, and {@code 202}
 *     write responses have no ETag.
 *     <p>
 *     {@code PUT} and {@code DELETE} accept these ETags in the {@code If-Match} and
 *     {@code If-None-Match} headers, exposing a limited ability to do conditional updates.
 *     Clients should treat the ETag as opaque and send it back verbatim. Using the {@code If-*}
 *     headers on such a node has the following effects:
 *     <ul>
 *       <li>{@code If-None-Match: *} — if the node already exists, no action is taken.</li>
 *       <li>{@code If-Match: {ETag}} — if the node does not exist, or its {@code revision} field
 *       has a different value, no action is taken.</li>
 *     </ul>
 *     Precondition failures are asynchronous no-ops: the endpoint still returns {@code 202 Accepted},
 *     and a client that needs to know whether a write applied must {@code GET} the node again.
 *     A syntactically valid {@code If-Match} value that does not encode a revision is a
 *     {@code 400 Bad Request}, as is supplying {@code If-Match} for a node without a suitable
 *     {@code revision} field.
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
	requires static spring.security.core;
	requires static spring.security.web;


	exports works.bosk.spring.boot;
}
