## Bosk-spring-boot developer's guide

This guide is for those interested in contributing to the development of the `bosk-spring-boot` module.
(The guide for developers _using_ the module is [USERS.md](../docs/USERS.md).)

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
- **Fail closed.** If access cannot be evaluated, the request is denied, not allowed.
- **Least surprise.** The endpoints behave like any other Spring Security resource;
  we use the framework's authentication and CSRF handling rather than inventing our own.
- **Use the framework.** Authorization is expressed in Spring Security terms
  (`GrantedAuthority`, `AccessDeniedException`).
- **Cross that bridge when you come to it.** We add only the access modes we can implement soundly.

#### Decisions

 **Explicit opt-in.**
Absence of configuration means absence of risk.
`UNSECURED` exists for local development, and selecting it when Spring Security is present
fails application startup, so a contradiction cannot silently relax security.

 **`AUTHENTICATED` declares an authority and uses the application's authentication.**
A request must be authenticated by Spring Security and carry `bosk:state`
(configurable with `bosk.web-api.maintenance.authority`).
We do not inspect the credential or decide how the caller authenticated.

 **CSRF is the application's policy.**
Under `AUTHENTICATED` the application's CSRF protection applies,
so a machine client needs a token unless the application chooses to exempt the path.
The library documents this rather than deciding it.

 **Authorization is enforced in the endpoint, not the filter chain.**
A library cannot rely on the application's security configuration.
Boot's default catch-all chain backs off as soon as the application declares its own `SecurityFilterChain`,
and a `securityMatcher`-scoped chain leaves the maintenance path unprotected.
Filter chains also compete for ownership of a URL:
a library-supplied chain would either pre-empt the application's filters or never run at all.
`MaintenanceEndpoints` instead calls `authorization.check(req)` itself, so the check runs
no matter which chain matched or whether any did.
It throws `AccessDeniedException`, letting `ExceptionTranslationFilter` produce the usual challenge or 403;
when no chain ran there is no entry point to challenge the caller,
so `AuthorityMaintenanceAuthorization` returns 403 directly.
The consequence is that the endpoint is safe by construction, but cannot be made _less_ restricted
than the configured authority; an application can still add restrictions,
including with `BoskMaintenanceRequestMatcher`.

 **No machine (bearer) mode.**
An earlier revision offered a `BEARER` mode that required `bosk:state` plus an `Authorization: Bearer` header,
and skipped CSRF for the path, on the premise that a non-ambient credential needs no CSRF protection.
The implementation could only check that the header was present, not that the request was authenticated by it,
so a session-authenticated request with a bogus header satisfied the mode while the authority came
from the ambient cookie; with permissive CORS, a cross-site request could exploit that.
Spring Security offers no reliable predicate for "was this credential ambient?"
(the `Authentication` hierarchy conflates OAuth2 login tokens with resource-server tokens),
so the mode could not be made sound in general.
Rather than ship a control that works sometimes and fails silently otherwise, we removed it,
leaving the machine-client case to the application, which can give clients a CSRF token
or, if the endpoint cannot be reached with ambient credentials, exempt the path.

#### Residual risks

- **No read-only mode.** `bosk:state` grants read and write over the whole tree, plus node creation.
  Path-sensitive authorization would subsume this, but does not exist yet.
- **The endpoint check is swallowable.** Because it throws `AccessDeniedException` from the controller,
  an application `@ControllerAdvice` can catch it and return a different status.
  The check runs before any state access, so this cannot cause an unauthorized mutation,
  but it can produce a misleading response.
- **Application configuration remains the application's responsibility.**
  The endpoint fails closed when it cannot authenticate,
  but if the application's own authentication is unsound, the endpoint inherits that.
- **`UNSECURED` is available without Spring Security.**
  A no-security application exposed on a network is wide open;
  the mode is loud and opt-in, but it cannot be enforced.
- **Writes fire hooks.** A maintenance write can have effects far beyond the state tree.
- **CSRF friction for machine clients.**
  With no bearer mode, a machine client behind CSRF-protected authentication must be given a token,
  or the application must exempt the path while ensuring it is not reachable with ambient credentials.

#### Naming

Configuration and classes name the capability (`maintenance`);
the URL and authority name the resource (`/bosk/state`, `bosk:state`).
These are deliberately two vocabularies: "state" is unambiguous when anchored by `bosk` or `bosk.web-api`,
but a bare configuration segment or class named `State` would be ambiguous.
