## Bosk-mongo developer's guide

This guide is intended for those interested in contributing to the development of the `bosk-mongo` module.
(The guide for developers _using_ the module is [USERS.md](../docs/USERS.md).)

### Introduction and Overview

`MongoDriver` is an implementation of the `BoskDriver` interface,
which permits users to submit updates to the bosk state.
As with any `BoskDriver`, the changes are applied asynchronously,
and some time later, the user can perform a read of the bosk and will see the updates.

Note, in particular, that `BoskDriver` is not concerned with read operations.
In bosk, all reads are served from the in-memory replica of the bosk state via `Reference.value()`,
and they never perform any database operations or other I/O.
Consequently, `MongoDriver` is concerned only with
(1) initializing the bosk's in-memory replica of the state from the database, and
(2) implementing state updates.

Like most drivers, `MongoDriver` acts as a stackable layer on top of another _downstream_ `BoskDriver`.
Drivers are expected to implement the `BoskDriver` semantics, describing the desired updates to the downstream driver.
From this description alone, a driver that simply forwarded all method calls to the downstream driver would be a valid implementation, but not an interesting one.
Real drivers aim to imbue a bosk with additional attributes it would not otherwise have.
In the case of `MongoDriver`, these additional attributes are:
- _replication_: multiple bosks can connect to the same MongoDB database, and any updates made by one bosk will be reflected in all of them; and
- _persistence_: `MongoDriver` allows an application to be shut down and restarted, and will find the bosk restored to the state it had at the time it shut down.

`MongoDriver` achieves these goals by implementing each update in two steps:
1. The bosk update is translated into a database update, and written to MongoDB. (It is _not_ forwarded to the downstream driver!)
2. Change stream events from MongoDB are translated back into bosk updates, and sent to the downstream driver.

Replication is achieved naturally by MongoDB when two or more `MongoDriver`s connect to the same database,
since the change stream will include all updates from all drivers; and
persistence is achieved by having
`MongoDriver` read the initial bosk state from the database during `Bosk` initialization (in the `initialRoot` method).

While conceptually simple, the real complexity lies in fault tolerance.
An important goal of `MongoDriver` is that if the database is somehow damaged and then repaired,
`MongoDriver` should recover and resume normal operation without any intervention.
This means `MongoDriver` must deal with a multitude of error cases and race conditions,
which add complexity to what would otherwise be a relatively straightforward interaction with MongoDB.
The MongoDB client library itself does an admirable job of fault tolerance
on its own for database reads, writes, and DDL operations;
but once the change stream is added to the mix, things get complicated,
because the change stream cannot operate in isolation:
change stream initialization and error handling must be coordinated with database reads
so that no change events are missed.

To manage this complexity,
a `MongoDriver` is actually composed of several objects with different responsibilities.
The happy-path database interactions are implemented by a `FormatDriver` object,
which rests on a kind of framework shell object called `MainDriver` that deals with
initialization, error handling, logging, change stream management, database transactions, and coordination between threads.
When anything goes awry, `MainDriver` can discard and replace the `FormatDriver` object,
which allows `FormatDriver` to be largely ignorant of fault tolerance concerns:
`FormatDriver` can simply throw exceptions when errors occur, and let `MainDriver` handle the recovery operations.

### Major components

A `MongoDriver` instance comprises a small cluster of objects that cooperate to deliver the desired functionality.

The top-level coordination is implemented by `MainDriver`, which serves as a kind of harness for the rest of the objects.
`MainDriver` is concerned with initialization, error handling, and coordination between threads.

The actual core implementation of driver semantics is mostly contained in `FormatDriver` implementations,
which handle the translation from bosk updates to MongoDB updates
and from MongoDB changes back to bosk updates passed to the downstream driver.
`MainDriver` manages the lifecycle of its `FormatDriver` object,
and has exactly one at any given moment.
Whenever an unexpected condition is encountered, `MainDriver` can respond by discarding the `FormatDriver`
and creating a new one.

The management of the change stream, by which `MongoDriver` learns of changes to the database,
is governed by `ChangeReceiver`, which houses the background thread responsible for
opening the cursor, waiting for events, forwarding them to the `FormatDriver` via the `ChangeListener` interface,
and handling cursor-related exceptions.
The `MainDriver` instantiates the `ChangeReceiver` and coordinates with it to ensure all change events and error conditions are handled appropriately.

### The `MongoDriver` interface

The `MongoDriver` interface extends the `BoskDriver` interface with two additional public methods, described below.

#### `refurbish`

The `refurbish` rewrites the entire database contents in the preferred format.
This supports evolution of the format, as well as evolution of the schema (the bosk state tree layout).
Users of `MongoDriver` use `MongoDriverSettings` to configure their preferred format options,
but if the database already exists, its format must take precedence.
The intent of the `refurbish` method is to overwrite the database contents so they are freshly serialized using the preferred format.

Conceptually, calling `bosk.driver().refurbish()` is very much like
calling `bosk.driver().submitReplacement(bosk.rootReference(), bosk.rootReference().value())`,
thereby replacing the entire database contents with a freshly serialized version.
However, there are three major differences between `refurbish` and this read-then-write operation:

1. `refurbish` is atomic, while read-then-write is not, and could be subject to the _lost update problem_;
2. `refurbish` changes the database to the preferred format specified in the `MongoDriverSettings`, while read-then-write preserves the current format; and
3. `refurbish` updates and modernizes the internal metadata fields, while read-then-write does not.

The implementation of `refurbish` uses a multi-document transaction to read, deserialize, re-serialize, and write the database contents atomically.

#### `close`

The `close` method shuts down a `MongoDriver` when the application is finished with it.
This is not often used in application, where usually a `Bosk` is a singleton
whose lifetime coincides with that of the application.
However, `close` is useful in unit tests,
to stop the operation of one driver so it doesn't unduly interfere with the next one.

`close` is done on a best-effort basis, and isn't guaranteed to cleanly shut down the driver in all cases.
In some cases, `close` can initiate an asynchronous shutdown that may not complete before `close` returns.

### The `FormatDriver` interface

The `FormatDriver` interface is a further extension of the `MongoDriver` interface
that describes the objects that do all _format-specific_ logic.
By "format" here, we mean the exact way in which the bosk state is mapped to and from database contents.

The `MainDriver` creates the appropriate `FormatDriver` subclass
based on the database contents, as described in its manifest document.
By design, if anything goes wrong in any part of the `FormatDriver` code,
including unforeseen exceptions,
the `FormatDriver` can be discarded and a new one created,
re-establishing the correspondence between the database and the in-memory bosk state.

This approach simplifies the implementations of the `FormatDriver` (which are already complex enough!)
by freeing them from the need to handle myriad unexpected or rare situations;
instead, the `FormatDriver` can simply crash and be replaced.

There are two ways in which a `FormatDriver` builds on the functionality of other `MongoDriver` classes, described below.

#### `loadAllState` and `initializeCollection`

The first feature that distinguishes a `FormatDriver` from a `MongoDriver`
is that two of the most challenging `MongoDriver` methods don't need to be implemented at all.

There are two `MongoDriver` methods that present special challenges to `FormatDriver`:
- `refurbish` requires cooperation between two potentially different formats, so it can't be implemented by any individual `FormatDriver`; and
- `initialRoot` requires cooperation between loading the database and opening a change stream cursor, and most of the complexity here is not format-specific.

For these reasons, `FormatDriver` does not implement these two methods,
but instead implements two simpler methods:
- `loadAllState` performs a database read of the entire bosk state and metadata, and returns it in a `StateAndMetadata` object; and
- `initializeCollection` performs a database write of the entire bosk state and metadata.

Given these two methods, `MainDriver` does the following:
- `refurbish` calls `loadAllState` on the detected `FormatDriver` and `initializeCollection` on the desired `FormatDriver`, thereby translating the format from one to the other; and
- `initialRoot` calls `loadAllState` on the detected `FormatDriver` and, if the state does not exist, delegates `initialRoot` to the downstream driver, and passes the result to `initializeCollection`.

In this way, two complex bosk operations filled with error handling and inter-thread coordination
are reduced to simpler database operations easily implemented by the `FormatDriver`.

#### `onEvent` and `onRevisionToSkip`

The second feature that distinguishes a `FormatDriver` from a `MongoDriver`
is that `FormatDriver` is expected to respond to change stream events.

The `onEvent` method is called by the `ChangeReceiver` (described below)
for each event received.
The `FormatDriver` is expected to perform the appropriate operations in order to communicate the change to the downstream driver;
or else it can throw `UnprocessableEventException` which causes the `ChangeReceiver` to reset
and reinitialize from scratch with a new `FormatDriver`.
In particular, this occurs when any bosk performs a `refurbish` operation,
since the database contents are changed in a manner so disruptive that
`onEvent` will naturally throw `UnprocessableEventException`.

The `onRevisionToSkip` method is called whenever the state is loaded via `loadAllState`.
This communicates to the `FormatDriver` that subsequent events should be ignored if they are older than
the revision found when the state was read from the database.

#### Division of responsibilities

Here is a summary of the responsibilities of `MainDriver` and `FormatDriver`.

`MainDriver`:
- Determines when the database collection should be initialized
- Detects the format, instantiates the `FormatDriver`, and connects it to the `ChangeReceiver`
- Ignores events on unrelated MongoDB databases and collections
- Implements `initialRoot` in terms of `loadAllState`
- Implements `refurbish` in terms of `loadAllState` and `initializeCollection`
- Handles `MongoException`
- Orchestrates disconnect/reconnect and retries
- Debounces reinitialization requests

`FormatDriver`:
- Implements `initializeCollection` and `loadAllState`
- Performs database reads and writes, aside from loading the manifest for format detection
- Interacts with the downstream driver, aside from `initialRoot`
- Implements `flush`
- Implements revisions, and ignores revisions older than the current one
- Ignores events on unrelated documents within the bosk collection (like the manifest)
- Processes updates to the manifest itself
	- Can throw `UnprocessableEventException` at its discretion,
	  but must not do so for any changes from its own `initializeCollection`
	  method, since this could lead to an endless loop of reinitialization.

#### `FormatDriver` implementation checklist

When your implementation is complete, you should be able to answer "yes" to the following questions:

- [ ] Do all the `MongoDriver` unit tests pass when they use your driver?
- [ ] Does your `onEvent` cope with any events generated by its own `initializeCollection`?
- [ ] Do you use checked exceptions for all exceptions you want to handle internally?
- [ ] Do you have enough `LOGGER.debug` statements that a developer can tell which code ran?
- [ ] Does `loadAllState` throw `UninitializedCollectionException` when it detects that the collection has not yet been initialized?

### `ChangeReceiver` and `ChangeListener`

If change stream events are the lifeblood of `MongoDriver`, then `ChangeReceiver` is its heart.
`ChangeReceiver` houses the background thread that manages the `MongoChangeStreamCursor` objects,
running a continual loop that initializes the cursor, waits for events, feeds them to a `ChangeListener`
and eventually closes the cursor if there's an error, at which point the loop starts over with a new cursor.

The `MainDriver` provides an implementation of the `ChangeListener` interface.
Ordinary update events are logged and forwarded to the `FormatDriver`;
other events are handled by `MainDriver` itself:

- `onConnectionSucceeded` detects the database format and instantiates the appropriate `FormatDriver` subclass;
- `onConnectionFailed` ensures the `MainDriver`'s internal state is consistent when the database is unreachable; and
- `onDisconnect` shuts down the `FormatDriver` when the database connection is lost.

In summary, these are the division of responsibilities among the components:
1. All format-specific logic is in `FormatDriver`
2. For format-independent logic:
- All change stream cursor lifecycle logic is in `ChangeReceiver`
- Everything that affects driver method implementation logic is in `MainDriver`
	- The foreground thread logic, implementing the driver methods, is in `MainDriver` itself
	- The background thread logic, implementing the _effects_ of change stream occurrences on driver methods, is in `MainDriver.Listener`, which implements `ChangeListener`

#### The cursor lifecycle

The lifetime of a `MongoDriver` is subdivided into a sequence of (ideally just one) connect-process-disconnect cycles.
The whole cycle is initiated by a single background thread,
and so these operations have almost no possibility for race conditions.

This design naturally responds to an error by disconnecting and reconnecting,
yet it does so in a way that shares logic with the normal initialization and shutdown operations.
This design reduces risk by transforming an unusual and risky occurrence (error handling and recovery)
into commonplace operations that happen every time an application runs (initialization and shutdown)
and are therefore well tested and well understood.

Between connection cycles, `MainDriver` enters a _disconnected_ state that sends no writes to the database.
This happens any time the driver detects that its understanding of the database contents may be faulty,
since the driver can no longer be trusted to modify the database.
Once the driver reestablishes a connection and reinitializes the appropriate `FormatDriver`,
it can resume normal operation.
A pleasant consequence of this design is that
the driver gracefully reinitializes if some other server performs a `refurbish` operation:
if the refurbish changes the format, then the driver will receive some change event it can't process,
causing it to reinitialize, which naturally re-reads the manifest and re-detects the format.
On the other hand, if the refurbish doesn't change the format,
then the driver will see no change events it can't handle,
and it will simply continue running without interruption.

`MainDriver` and `ChangeReceiver` have a lifetime that coincides with that of the `Bosk`.
`FormatDriver` and `FlushLock` have a lifetime that coincides with that of the cursor.

### Miscellaneous development notes

Whenever possible, all error conditions handled internally are signaled by checked exceptions,
meaning the Java compiler can help us ensure that we have handled them all.
In general, we do not shy away from defining new types of exceptions,
erring on the side of adding a new exception for clarity rather than reusing an existing one.

Testing is performed by JUnit 5 tests using Testcontainers and Toxiproxy.
Testcontainers allows us to verify our database interactions against an actual MongoDB database,
while Toxiproxy allows us to induce various network errors and verify our response to them.
(Mocks are not generally suitable for testing error cases,
since the precise exceptions thrown by the client library are not well documented,
and since they are runtime exceptions, we get no help from the Java compiler.)
We have developed a few of our own JUnit 5 extensions to make this testing more convenient,
such as `@InjectedTest` and `@DisruptsMongoProxy`.

#### Logging guidelines

Log `warn` and `error` levels are likely to be logged by applications in production,
and so they will be visible to whatever team is operating the application that uses Bosk.
They should be written with a reader in mind who is not a Bosk expert, perhaps not even
knowing what bosk is, and should contain enough information to guide their troubleshooting efforts.

Logs at `info` levels can target knowledgeable Bosk users, and should aim to explain what
the library is doing in a way that helps them learn to use it more effectively.

Logs at the `debug` level target Bosk developers. They can use some Bosk jargon, though
they should also be helping new Bosk developers climb the learning curve. They should
allow developers to tell what code paths executed.

Logs at the `trace` level target expert Bosk developers troubleshooting very tricky bugs,
and can include information that would be too voluminous to emit under most circumstances.
Examples include stack traces for routine situations, or dumps of entire data structures,
neither of which should be done at the `debug` level. It can also include high-frequency messages
emitted many times for a single user action (again, not recommended at the `debug` level),
though this must be done cautiously, since even disabled log statements still have nonzero overhead.
