### Hello World example project

Ok, this app doesn't do much,
but it does demonstrate how easy it is to get basic bosk functionality in a Spring Boot 3 project.

#### Jackson `ObjectMapper` configuration

The `bosk-spring-boot` module automatically configures Jackson
to do JSON serialization and deserialization of the bosk state tree objects,
via the `bosk-jackson` module.

#### Automatic `ReadSession`

A bosk `ReadSession` provides a lightweight thread-local snapshot of the bosk state
for the duration of an operation.
The `bosk-spring-boot` module automatically establishes a `ReadSession` around every HTTP servlet method,
using the `ReadSessionFilter` class.
(This can be disabled by adding the line `bosk.web.read-session=false` to `application.properties`.)

#### Maintenance endpoints

By setting `bosk.web.maintenance.access=AUTHENTICATED` in `application.properties`,
the `bosk-spring-boot` module creates `GET`, `PUT`, and `DELETE` endpoints
under `/bosk/state` (the default) that allow users to view and modify the bosk contents over HTTP.
Set `bosk.web.maintenance.path` to serve them under a different path.

#### Security

The maintenance endpoints run in `AUTHENTICATED` mode, so the application must authenticate requests
to their path. `HelloSecurityConfig` authenticates a bearer token and requires the `bosk:state`
authority for the maintenance path. A real application would introspect the token against an
authorization server, or validate a JWT. All other endpoints are left open, as they were before
this class existed.

Because this example has no browser session, `HelloSecurityConfig` disables CSRF protection. The
`main.tf` example points the Bosk Terraform provider at these endpoints, but that provider supports
only `UNSECURED` or HTTP Basic, not bearer tokens, so it cannot talk to these bearer-protected
endpoints; it would work only if the example were changed to drop Spring Security and use
`bosk.web.maintenance.access=UNSECURED`.
