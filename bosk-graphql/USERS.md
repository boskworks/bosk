# Bosk GraphQL user guide

`BoskGraphQL.schemaFor(bosk)` builds a GraphQL schema from a Bosk state tree:

```java
GraphQLSchema schema = BoskGraphQL.schemaFor(bosk);
GraphQL graphQL = GraphQL.newGraphQL(schema).build();
```

The schema is read-only: it exposes a single `Query` type and no mutations, because changes go through the Bosk API rather than GraphQL. The root node's record components become the top-level query fields, and every record type reachable from there becomes a GraphQL object type. Record-typed fields are resolved lazily, so recursive and mutually-referencing types work.

The example-hello project has a working Spring Boot endpoint, including query depth and complexity limits.

This guide describes the decisions we made about how Bosk concepts map onto GraphQL. It doesn't explain GraphQL itself.

## Components become fields

A `StateTreeNode` is a record, so each component becomes a field, named after the component. Primitive and common types map to the obvious scalars:

| Java type | GraphQL type |
| --- | --- |
| `String`, `char` | `String` |
| `int`, `short`, `byte` | `Int` |
| `long` | `Long` (custom scalar) |
| `float`, `double` | `Float` |
| `boolean` | `Boolean` |
| `BigDecimal` | `BigDecimal` (custom scalar) |
| `BigInteger` | `BigInteger` (custom scalar) |
| `Identifier` | `Identifier` (custom scalar) |
| enum | a GraphQL enum with the same constants |

A component maps to a non-null field, except when its type is `Optional`, which makes the field nullable.

## Containers

Bosk's container types don't map one-to-one onto GraphQL, so each has a decided representation:

- `ListValue<T>` is a list of `T`.
- `Catalog<E>` is a list of `E`, with an optional `id: Identifier` argument that selects a single entry.
- `MapValue<V>` is a list of `{ key, value }` entries, with an optional `key: String` argument.
- `SideTable<K, V>` is a list of `{ path, value }` entries, with an optional `id: Identifier` argument. `path` is the entry's reference path.
- `Reference<T>` is a single `{ path, value }` object, where `value` is null if the reference resolves to nothing.
- `Optional<T>` is a nullable field of `T`'s type. An `Optional` wrapping one of the containers above is also nullable.

### Listings are dereferenced

`Listing<E>` is the one type whose GraphQL representation deliberately differs from how the rest of Bosk treats it. Elsewhere, a listing behaves like a `SideTable` whose value type is the unit type, which would surface as a list of paths. For GraphQL we instead treat a listing as a list of the referenced entities: each entry is `{ path, value }`, where `value` is the referenced `E`, or null if the entry points at no entity. This lets a client traverse a listing's entries directly. As with `Catalog` and `SideTable`, an optional `id: Identifier` argument selects a single entry.

## Tagged unions

A `TaggedUnion<C>` maps to a GraphQL interface named after the case supertype `C`, and each case maps to an object type that implements the interface. The interface has a `tag` field; each case type has the same `tag` field plus fields for the case's own components.

The `tag` is the discriminator: a value resolves to the case type whose case-map entry has the value's `tag()`. A `tag()` with no entry in the case map fails rather than falling back to the value's runtime class.

```graphql
{
  shape {
    tag
    ... on Circle { radius }
    ... on Square { side }
  }
}
```

## Parameterized types

A record used with type arguments is a distinct GraphQL type for each parameterization, named by appending the arguments, joined with underscores: `GenericNode<String>` becomes `GenericNode_String`, and `GenericNode<Integer>` becomes `GenericNode_Integer`.

## Names

GraphQL type names come from the Java class's simple name. Because the underscore joins type arguments, a class whose simple name already contains underscores, or starts with `u`, is prefixed with `u` and its underscore count, so the encoding stays unambiguous:

- `Foo_Bar` → `u1Foo_Bar`
- `util` → `u0util`

Names that contain `$` or start with `_` are rejected.

## Errors

Schema generation fails with:

- `UnsupportedNameException` if a type or field name can't be represented in GraphQL, such as a duplicate name or a name containing a reserved character.
- `UnsupportedTypeException` if a Java type has no GraphQL representation, such as a parameterized type outside a record field.
