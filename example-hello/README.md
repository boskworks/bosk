### Hello World example project

Ok, this app doesn't do much,
but it does demonstrate how easy it is to get basic bosk functionality in a Spring Boot project.

#### Jackson `ObjectMapper` configuration

The `bosk-spring-boot` module automatically configures Jackson
to do JSON serialization and deserialization of the bosk state tree objects,
via the `bosk-jackson` module.

#### Automatic `ReadSession`

A bosk `ReadSession` provides a lightweight thread-local snapshot of the bosk state
for the duration of an operation.
The `bosk-spring-boot` module automatically establishes a `ReadSession` around every HTTP servlet method,
using the `ReadSessionFilter` class.
(This can be disabled by adding the line `bosk.web-api.read-session=false` to `application.properties`.)

#### Maintenance endpoints

By setting `bosk.web-api.maintenance.access=AUTHENTICATED` in `application.properties`,
the `bosk-spring-boot` module creates `GET`, `PUT`, and `DELETE` endpoints
under `/bosk/state` that allow users to view and modify the bosk contents over HTTP.
The access must be set explicitly because these endpoints expose full access to the state tree.
`AUTHENTICATED` requires the `bosk:state` authority, as described below.

#### Security

The maintenance endpoints run in `AUTHENTICATED` mode, so they require an authenticated request
carrying the `bosk:state` authority. `HelloSecurityConfig` provides a minimal example: it
authenticates a bearer token against the `example.security.tokens` property and grants the
authority. A real application would introspect the token against an authorization server, or
validate a JWT. All other endpoints are left open, as they were before this class existed.

Because this example has no browser session, `HelloSecurityConfig` disables CSRF protection: a
request can only be authenticated by a token the client sends explicitly, so there are no ambient
credentials to forge. An application with browser sessions should keep CSRF protection on and give
machine clients a token; see the `bosk-spring-boot` module javadoc for details.

The `main.tf` example talks to these endpoints, so its provider must send a bearer token too.
