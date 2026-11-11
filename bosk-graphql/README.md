## bosk-graphql

This is the subproject for the published `bosk-graphql` library,
containing [`BoskGraphQL`](src/main/java/works/bosk/graphql/BoskGraphQL.java)
for generating a read-only GraphQL schema from a Bosk state tree.

See [USERS.md](USERS.md) for a guide to how Bosk types map onto GraphQL.

See the [javadocs](https://javadoc.io/doc/works.bosk/bosk-graphql/latest/works.bosk.graphql/module-summary.html) for more information.

### Usage

```java
GraphQLSchema schema = BoskGraphQL.schemaFor(bosk);
GraphQL graphQL = GraphQL.newGraphQL(schema).build();

// BoskGraphQL builds the schema; the caller owns the read session.
try (var _ = bosk.readSession()) {
    ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput()
        .query("{ widgets { id name } }")
        .build());
}
```

`ReadSessionFilter` from `bosk-spring-boot` opens a read session for `GET`,
`HEAD`, and `OPTIONS`, but a GraphQL endpoint usually accepts `POST`, so it must
open its own session. A request with `Cache-Control: no-cache` is flushed by that
filter before the handler runs; without it, a query sees the state as of its
read session, not necessarily the latest submitted update.

### Limits

`BoskGraphQL` does not impose query depth or complexity limits. Since the schema
is generated reflectively and may contain recursive types and reference values,
configure instrumentation before exposing the endpoint:

```java
GraphQL graphQL = GraphQL.newGraphQL(schema)
    .instrumentation(new ChainedInstrumentation(List.of(
        new MaxQueryDepthInstrumentation(20),
        new MaxQueryComplexityInstrumentation(1000))))
    .build();
```

### SDL and code generation

`BoskGraphQL.sdlFor(bosk)` returns the schema as SDL, which can be fed to
GraphQL client code generators without running an introspection query. When
configuring codegen, map the custom scalars explicitly:

| GraphQL scalar | Java/JSON representation |
| --- | --- |
| `Identifier` | `works.bosk.Identifier`; serialized as a string |
| `Path` | `works.bosk.Path`; serialized as a URL-encoded string |
| `Long` | 64-bit integer; serialized as a string to preserve precision |
| `BigDecimal` | `java.math.BigDecimal`; serialized as a string |
| `BigInteger` | `java.math.BigInteger`; serialized as a string |

### Read-only

The generated schema has a `Query` type only. Mutations are deliberately left
out: writes go through the Bosk driver, including the maintenance endpoints when
they are enabled.
