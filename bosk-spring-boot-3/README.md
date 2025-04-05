## bosk-spring-boot-3

This is the subproject for the published `bosk-spring-boot-3` library for
Spring Boot support.

It offers the features described below.

### Automatic read context

[`ReadContextFilter`](src/main/java/works/bosk/spring/boot/ReadContextFilter.java)
opens a _read context_ automatically for every `GET`, `HEAD`, and `OPTIONS` request.
In many cases, this is sufficient that the application need never open its own read contexts,
except for `POST` operations that are acting like a `GET` with a body,
or background operations executed on a separate thread.

This feature is enabled by default, and can be disabled by setting `bosk.web.read-context` to `false`.
(Note, though, that if you have a situation where you need more control over read contexts,
you can consider using `Bosk.supersedingReadContext()` rather than turning off automatic read contexts globally.)

### Maintenance endpoints

The [`MaintenanceEndpoints`](src/main/java/works/bosk/spring/boot/MaintenanceEndpoints.java) component
registers HTTP endpoints giving direct `GET`, `PUT`, and `DELETE` access to the bosk state in JSON form.
The endpoints have a prefix based on the `bosk.web.maintenance-path` setting,
followed by the path of the node within the bosk state.

The endpoints also support ETags via `If-Match` and `If-None-Match` headers,
which exposes a limited ability to do conditional updates.
Nodes participating in this feature must have a field called `revision` of type `Identifier`.
Using the `If-` headers on such a node has the following effects:

- `If-None-Match: *`: if the node already exists, no action is taken.
- `If-Match: {ID}`: if the node does not exist, or its `revision` field has a different value, no action is taken.
