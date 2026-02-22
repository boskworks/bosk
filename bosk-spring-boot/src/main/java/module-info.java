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
 *     Endpoints are prefixed by the value of the {@code bosk.web.maintenance-path} setting, followed by the
 *     path of the node within the Bosk state. They are intended for troubleshooting, manual operations, or
 *     integration with external systems that need full control over the Bosk state.
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

	requires static lombok;

	exports works.bosk.spring.boot;
}
