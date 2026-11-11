# Bosk maintenance API description

`bosk-spring-boot` serves an OpenAPI 3.1 document describing the maintenance endpoints and the
JSON representation of the application's state tree. It is available whenever the maintenance
endpoints are registered:

```
GET <bosk.web.maintenance.path>/openapi.json
```

For example, with the default path, `GET /bosk/state/openapi.json`. The document is generated from
the state tree's types at initialization. It is served beneath the maintenance path, so the
application's authentication rule for the maintenance endpoints covers it too.

The document has no dependency on springdoc. Applications that already use springdoc can fetch the
document and serve it alongside their own, or use it with any OpenAPI-aware client, code generator,
or documentation viewer such as Swagger UI or Scalar.

## Paths

Every addressable node in the state tree becomes a path. The base path is the maintenance path, and
each subsequent segment names a record component, a container entry, or a tagged-union case.

A node's JSON value embeds its children. For example, if `/bosk/state` contains a record field
`targets` holding a `Catalog<Target>`, then `GET /bosk/state` includes the targets, and the same
targets are also available individually:

```
/bosk/state
/bosk/state/targets
/bosk/state/targets/{targetId}
```

Container entries use a path parameter for the entry's identifier. The parameter name is derived
from the container's key type, so a `Catalog<Target>` and a `SideTable<Target, V>` both use
`{targetId}`. Identifiers are URL-encoded as a single path segment. An identifier containing a
slash must therefore be sent as `%2F`; note that Spring Security's default `StrictHttpFirewall`
rejects such URLs unless the application customizes its firewall.

Recursive types are expanded until a record type repeats along the current path. The schemas use
`$ref`, so the recursion is visible there even though the path list does not repeat forever.

## Operations

The document describes the operations that the endpoint actually supports for each node:

| Node | `GET` | `PUT` | `DELETE` |
| --- | --- | --- | --- |
| Root | yes | yes | no |
| Record field | yes | yes | no |
| `Optional` field | yes | yes | yes |
| `@Self` or `@Enclosing` reference | yes | no | no |
| `Phantom` field | yes (always `404`) | no | yes (no-op) |
| `Catalog`, `SideTable`, or `Listing` field | yes | yes | no |
| Container entry | yes | yes | yes |
| Tagged-union value | yes | yes | no |
| Tagged-union case and everything below it | yes | no | no |

`PUT` and `DELETE` return `202 Accepted` because the write is submitted through `bosk.driver()`.
The response means the write was submitted, not that it has been applied. Writes fire hooks and any
downstream replication or persistence.

`PUT` to a `Catalog` or `SideTable` entry whose value is an `Entity` requires the value's `id` to
equal the path identifier. A `Listing` entry is a unit value: `true` is a nonce that indicates the
entry is present, so its request body must be `true`.

A `404` from a write means the path is not a valid Bosk reference. A write to a path whose enclosing
node does not currently exist is silently ignored: it still returns `202 Accepted`, and a client
that needs to know whether it applied must `GET` the node again.

## Tagged unions

A tagged union is serialized as a single-property object whose property name is the case tag:

```json
{"circle": {"radius": 1.5}}
```

The union itself is a normal node. Its schema is a `oneOf` of the case objects.

Each case is also its own path, with the tag as a literal segment:

```
/bosk/state/shape
/bosk/state/shape/circle
/bosk/state/shape/circle/radius
```

A case path returns the case value without the wrapper. If the union currently holds a different
case, the path is a `404`. Case paths and everything below them are read-only because Bosk does not
permit replacing or deleting a value inside a `TaggedUnion`; to change the case, `PUT` the union
itself.

## JSON representation

The schemas mirror `JacksonSerializer`, not Jackson's default mapping for records. In particular:

- A record is an object. Fields of type `Optional` are omitted when empty and are not in
  `required`. Fields of type `Phantom` are omitted entirely and are always empty. Implicit `@Self`
  and `@Enclosing` references are omitted from the JSON but remain readable as paths. A field marked
  with `@Polyfill` is not required, because an older stored value may omit it.
- `Identifier` and `Reference` are strings. A reference is a URL-encoded Bosk path.
- `ListValue<T>` is an array of `T`.
- `MapValue<T>` is an object whose values are `T`.
- `Catalog<E>` is an array of single-entry objects, each mapping an identifier to an entity:
  `[{"w1": {...}}]`. This is not a JSON object keyed by identifier.
- `SideTable<K, V>` is `{"valuesById": [{"w1": {...}}], "domain": "/path/to/catalog"}`.
- `Listing<E>` is `{"ids": ["w1"], "domain": "/path/to/catalog"}`. A listing entry is the boolean
  `true`: a nonce for the unit value that indicates the entry's presence.
- A `TaggedUnion` is a single-property object as described above.
- Enums are strings with an `enum` list.

## Conditional writes

A `GET` of a node with a field named `revision` of type `Identifier` includes an `ETag` header.
Send that value back verbatim in `If-Match` to apply a `PUT` or `DELETE` only if the node still has
that revision. `If-None-Match: *` applies the write only if the node does not already exist.

For reads, send the same value in `If-None-Match` to get a `304 Not Modified` with no body while the
node's revision is unchanged. Lists and weak tags are accepted, and `*` matches any node with a
`revision` field; a node without one has no ETag, so `If-None-Match` never matches there. `If-Match`
has no effect on `GET` or `HEAD`.

A precondition mismatch is a `202` no-op, not `412 Precondition Failed`, because the precondition
check and the write submission are not atomic. A syntactically invalid `If-Match` value, or
`If-Match` on a node without a suitable `revision` field, is a `400`.

## Security

The document describes the shape of the state tree, including field names and enum values. It is
served under the maintenance path so that it cannot be reached without the same authorization as
the endpoints it describes. If you restrict access to specific paths within the maintenance tree,
make sure the OpenAPI document is reachable too, or it will return whatever your filter chain does
for an unauthorized request.
