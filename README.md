![Release status](https://github.com/boskworks/bosk/actions/workflows/release.yml/badge.svg)
[![Maven Central](https://img.shields.io/maven-central/v/works.bosk/bosk-core)](https://central.sonatype.com/namespace/works.bosk)

# Bosk

![Three inquisitive cartoon trees with eyes](/art/bosk-3trees-wide-small.png)

Bosk is a Java library for state management in distributed systems.
It eases the journey from a simple standalone application to a high-availability clustered replica set
by supporting reactive, idempotent, deterministic control logic design patterns,
using an immutable in-memory state tree for ultra-fast reads (~50ns).

First, you start with just the `bosk-core` library, writing your application's control plane logic using Bosk.
The the built-in integrations with things like Jackson and Spring Boot help you get your application up and running.

Then, when the time comes to turn your standalone application into a high-availability replica set,
you can bring in the optional [MongoDB library](bosk-mongo).
The hard work of change propagation, ordering, durability, consistency, atomicity, and observability,
as well as fault tolerance, and emergency manual state inspection and modification,
is all delegated to MongoDB: a well-known, reliable, battle-hardened codebase.
You don't even need to trust Bosk to get all these details right:
all we do is send updates to MongoDB, and maintain the in-memory replica by following the MongoDB change stream.

If you'd rather use SQL instead of MongoDB, there's an experimental [SQL library](bosk-sql) too.

## Documentation
- [User's Guide](docs/USERS.md)

Each of the published libraries has its own `README.md` and its own Javadocs;
for example, the `bosk-core` library has a [`README.md`](bosk-core/README.md)
and [Javadocs](https://javadoc.io/doc/works.bosk/bosk-core/latest/works.bosk.core/module-summary.html).

## Getting Started

The [bosk-core](bosk-core) library is enough to create a `Bosk` object and start writing your application.

You can define your state tree's root node as a java Record, like this:

```
import works.bosk.StateTreeNode;
import works.bosk.Identifier;

public record ExampleState (
	// Add fields here as you need them
	String name
) implements StateTreeNode {}
```

Now declare your singleton `Bosk` class to house and manage your application state:

```
import works.bosk.Bosk;
import works.bosk.DriverFactory;
import works.bosk.Reference;
import annotations.works.bosk.ReferencePath;
import exceptions.works.bosk.InvalidTypeException;

@Singleton // You can use your framework's dependency injection for this
public class ExampleBosk extends Bosk<ExampleState> {
	public ExampleBosk() throws InvalidTypeException {
		super(
			"ExampleBosk",
			ExampleState.class,
			defaultRoot(),
			driverFactory());
	}

	public interface Refs {
		// Typically, you add a bunch of useful references here, like this one:
		@ReferencePath("/name") Reference<String> name();
	}

	public final Refs refs = rootReference().buildReferences(Refs.class);

	private static ExampleState defaultRoot() {
		return new ExampleState("world");
	}

	// Start off simple
	private static DriverFactory<ExampleState> driverFactory() {
		return Bosk.simpleDriver();
	}
}
```

You create an instance of `ExampleBosk` at initialization time,
typically using your application framework's dependency injection system.

To read state, acquire a `ReadContext`, providing access to a lightweight immutable snapshot of your state tree:

```
try (var _ = bosk.readContext()) {
	System.out.println("Hello, " + bosk.refs.name.value());
}
```

A read context is intended to be coarse-grained, for example covering an entire HTTP request,
giving you "snapshot-at-start" semantics and protecting you from race conditions.
It is an antipattern to use many small read contexts during the course of a single operation.

This part is done automatically for you in Spring Boot if you bring in the `bosk-spring-boot` library.
You can also set the `bosk.web.maintenance-path` property to get an immediate HTTP REST API to view and edit your state tree.

To modify state programmatically, use the `BoskDriver` interface:

```
bosk.driver().submitReplacement(bosk.refs.name(), "everybody");
```

During your application's initialization, you can register a callback hook to perform an action whenever state changes:

```
bosk.registerHook("Name update", bosk.refs.name(), ref -> {
	System.out.println("Name is now: " + ref.value());
});
```

After this, you can add in other packages as you need them,
like [bosk-jackson](bosk-jackson) for JSON serialization.
or [bosk-mongo](bosk-mongo) for persistence and replication.
Use the same version number for all packages.

### Replication

When you're ready to turn your standalone app into a replica set,
add [bosk-mongo](bosk-mongo) as a dependency
and change your Bosk `driverFactory` method to substitute `MongoDriver` in place of `Bosk.simpleDriver()`:

```
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import mongo.drivers.works.bosk.BsonSerializer;
import mongo.drivers.works.bosk.MongoDriver;
import mongo.drivers.works.bosk.MongoDriverSettings;

...
	private static DriverFactory<ExampleState> driverFactory() {
		MongoClientSettings clientSettings = MongoClientSettings.builder()
			.build();

		MongoDriverSettings driverSettings = MongoDriverSettings.builder()
			.database("exampleDB")
			.build();

		// For advanced usage, you'll want to inject this object,
		// but for getting started, we can just create one here.
		BsonSerializer bsonSerializer = new BsonSerializer();

		return MongoDriver.factory(
			clientSettings,
			driverSettings,
			bsonSerializer);
	}
```

To run this, you'll need a MongoDB replica set.
You can run a single-node replica set using the following `Dockerfile`:

```
FROM mongo:7.0
RUN echo "rs.initiate()" > /docker-entrypoint-initdb.d/rs-initiate.js
CMD [ "mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all" ]
```

Now you can run multiple copies of your application, and they will share state.

## Development

### Code Structure

The repo is structured as a collection of subprojects because we publish several separate libraries.
[bosk-core](bosk-core) is the main functionality, and then other packages like [bosk-mongo](bosk-mongo) and [bosk-jackson](bosk-jackson)
provide integrations with other technologies.

The subprojects are listed in [settings.gradle](settings.gradle), and each has its own `README.md` describing what it is.

### Versioning

In the long run, we'll use the usual semantic versioning.

For 0.x.y releases, all bets are off, and no backward compatibility is guaranteed.

Releases with four numbers, like a.b.c.d, are "tail" releases containing
important fixes to old releases.

### Logo

Logo was derived from this public-domain image: https://openclipart.org/detail/44023/small-trees-bushes

Modified by Grady Johnson.
