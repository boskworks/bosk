## Bosk-spring-boot developer's guide

This guide is for those interested in contributing to the development of the `bosk-spring-boot` module.
(The guide for developers _using_ the module is [USERS.md](../docs/USERS.md) and the
`bosk-spring-boot` javadoc, especially `MaintenanceAccess`.)

### Maintenance endpoint security

`MaintenanceEndpoints` exposes `GET`, `PUT`, and `DELETE` over the entire Bosk state tree as JSON.
This section records why the access control is shaped the way it is,
so future changes can be judged against the same reasoning.

#### Threat model

The asset is the state tree: its contents, and more importantly its integrity.
A `PUT` or `DELETE` is submitted through `bosk.driver()`, so it fires hooks, replicates, and persists.
An unauthorized write is therefore a durable change to the application's control plane
with arbitrary downstream effects, not merely a bad response.
An unauthorized read discloses whatever the tree holds.

The attackers we care about are:

1. an unauthenticated network peer;
2. a browser acting on a user's ambient credentials (a session cookie), that is, CSRF;
3. an authenticated but under-privileged user; and
4. a peer inside the trust boundary holding valid credentials.

#### Principles

- **Secure by default.** The endpoints are not registered unless an access is chosen; `NONE` is the default.
- **Fail closed.** If access cannot be evaluated, the application does not start, rather than serving
  the endpoints unprotected.
- **Least surprise.** Authorization is the application's, expressed in its own Spring Security
  configuration; the library adds no policy of its own.
- **Use the framework.** We rely on Spring Security's own filter chain and authorization managers.
- **Cross that bridge when you come to it.** We support only the access modes we can implement soundly.

#### Decisions

 **Explicit opt-in.**
Absence of configuration means absence of risk.
`UNSECURED` exists for local development, and selecting it when Spring Security is present
fails application startup, because "I don't care about security" and "I have Spring Security"
are contradictory.

 **`AUTHENTICATED` verifies the application's authentication rather than enforcing its own.**
The application must authenticate requests to the maintenance path, and chooses its own policy,
typically a one-line rule such as `requestMatchers(path + "/**").hasAuthority("bosk:state")`.
The library does not require a particular authority, and does not check anything per request.

This reverses an earlier design that enforced a `bosk:state` authority inside the endpoint.
That enforcement worked regardless of the application's configuration, but it meant the library
invented an authority-policy mechanism and could not be made sound for credential provenance
(it could not tell whether a request's credentials were ambient). Delegating to the application's
filter chain lets us use the framework's own, well-understood mechanism.

The cost of delegating is that a missing rule would silently leave the endpoints open.
We close that gap with a startup check: in `AUTHENTICATED` mode the application refuses to start
unless its filter chain denies unauthenticated requests to the maintenance path.
This is why the endpoints can be "unprotected" at runtime yet still safe by default.

 **The startup check.**
`MaintenanceEndpointSecurityCheck` asks Spring Security's `FilterChainProxy` for the filter chain
matching the maintenance path, finds its `AuthorizationFilter`, and evaluates its
`AuthorizationManager` for the path as if the request were unauthenticated. If no chain matches, or
the chain would allow an anonymous request, it throws `IllegalStateException` and the application
fails to start with a message naming the path and the rule to add.

The check deliberately knows nothing about *how* the application authenticates: bearer tokens,
sessions, mTLS, or anything else are all fine, as long as anonymous access is denied.

#### Residual risks

- **No deliberately-public mode with Spring Security.** `UNSECURED` is rejected when Spring
  Security is present, and `AUTHENTICATED` rejects an anonymous-accessible path. An application
  that wants the endpoints public while using Spring Security cannot express that through the
  library; it must bypass the endpoints or remove Spring Security.
- **Writes fire hooks.** A maintenance write can have effects far beyond the state tree.
- **The check uses Spring Security internals.** `FilterChainProxy`, `AuthorizationFilter`, and
  `AuthorizationManager` are public APIs, but the check depends on their shape across versions.

#### Naming

Configuration and classes name the capability (`maintenance`);
the URL and authority vocabulary suggests the resource (`/bosk/state`, `bosk:state`).
These are deliberately two vocabularies: "state" is unambiguous when anchored by `bosk` or `bosk.web`,
but a bare configuration segment or class named `State` would be ambiguous.
