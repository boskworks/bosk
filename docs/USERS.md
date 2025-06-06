## User's Guide

Check out the Table of Contents to help you find what you're looking for.
In particular, check out the [Recommendations](#recommendations) section.

### The Bosk Object

The `Bosk` object is a container for your application state tree.
If it helps, you can picture it as an `AtomicReference<MyStateTreeRoot>` that your application can access,
though it actually does several more things:
- it acts as a factory for `Reference` objects, via `RootReference`, which provide efficient access to specific nodes of your state tree,
- it provides stable thread-local state snapshots, via `ReadContext`,
- it provides a `BoskDriver` interface, through which you can modify the immutable state tree,
- it propagates telemetry information, via `BoskDiagnosticContext`, and
- it can execute _hook_ callback functions when part of the tree changes.

The `Bosk` object is typically a singleton.

#### Initialization

Initialization of the `Bosk` object happens during its constructor.
Perhaps this seems self-evident, but it means a lot happens during the constructor, including running user-supplied code, in order to establish the initial bosk state invariants.

There are two primary things that need initializing:
- The `BoskDriver`
- The state tree

##### Driver initialization

First, the driver is initialized by calling the `DriverFactory` function passed in to the bosk constructor.
The driver factory is an important bosk extension point that allows functionality to be customized.
Every `Bosk` object has a _local driver_, which applies updates directly to the in-memory state tree;
the `DriverFactory` allows this to be extended with additional functionality by stacking "decorator" layers on top of the local driver.

The `DriverFactory` function is invoked in the `Bosk` constructor as follows:

``` java
this.driver = driverFactory.build(boskInfo, localDriver);
```

...where `boskInfo` provides access to information about the `Bosk` object.

The return value of this function is stored, and can be retrieved later via `Bosk.driver()`
(though the actual object returned by this method could have additional layers stacked on top).

##### State tree initialization

The state tree (described below) is initialized by calling `driver.initialState`.
Drivers are free to choose how the initial state is computed: they can supply the initial state themselves, or they can delegate to a downstream driver.
For example, `MongoDriver` will load the initial state from the database if it's available, and if not, it will delegate to the downstream driver.

If all drivers choose to delegate to their downstream drivers, ultimately the `initialState` method of the bosk's local driver will be called.
This method calls the `Bosk` constructor's `DefaultRootFunction` parameter to compute the initial state tree.
The overall effect of this setup is that the `DefaultRootFunction` parameter is only used if the bosk's driver does not supply the initial state.

### The State Tree

Your application state takes the form of a tree of immutable node objects that you design.
The `Bosk` object keeps track of the root node, and the other nodes can be reached by traversing the tree from the root.

#### Paths and References
A node is identified by the sequence of steps required to reach that node from the root node.
The step sequence can be represented as a slash-delimited _path_ string;
for example, the path `"/a/b"` represents the node reached by calling `root.a().b()`.
All paths start with a slash.

##### Path objects

A `Path` object is the parsed form of a path string.
They can be created in three ways:
1. The `Path.parse` method accepts a slash-delimited path string. The segments of the path must be URL-encoded.
2. The `Path.of` method accepts a sequence of segment strings. The segments are not URL-encoded.
3. The `Path.then` method extends a path with additional trailing segments, also not URL-encoded.

For example, the following three `Path` objects are identical:
- `Path.parse("/media/films/Star%20Wars")`
- `Path.of("media", "films", "Star Wars")`
- `Path.of("media", films").then("Star Wars")`

Path objects are always _interned_, meaning that two identical paths are always represented by the same `Path` object.

Paths are validated upon creation to make sure they are properly formatted;
otherwise, a `MalformedPathException` is thrown.

##### Reference objects

A `Reference` is a pointer to a node in the state tree for a particular bosk, identified by its path.

`Reference` is one of the most important classes in Bosk. It is used in two primary ways:

1. A `Reference` can be created by the application (typically during initialization) to indicate a particular node to access, either to read its current value or to submit an update.
2. A `Reference` can be stored in the state tree itself to point to another node in the tree. (A `Reference` node cannot point to another `Reference` node.)

Unlike `Path`, `Reference` is type-checked upon creation to make sure it refers to an object that _could_ exist.
It is valid for a reference to refer to an object that _does not currently_ exist
(such as an `Optional.empty()` or a nonexistent `Catalog` entry),
but attempting to create a reference to an object that _cannot_ exist
(such as a nonexistent field of an object) results in an `InvalidTypeException`.

Two `Reference` objects are considered equal if they have the same path and the same root type.
In particular, references from two different bosks can be equal,
despite their `value()` methods returning different objects.

#### Node types

##### `StateTreeNode` and `Entity`

`StateTreeNode` is a marker interface you use to indicate that your class can be stored in a bosk.
It has no functionality.

`Entity` is a `StateTreeNode` that has a method `id()` returning an `Identifier`.
This allows them to be stored in a `Catalog` (see below).

State tree node classes must be `record` types.
A node is considered to _contain_ its fields, creating a whole/part parent/child relationship between them.
Removing a node removes all its descendant nodes.
Diamond relationships, where two nodes have the same child, are not _prevented_, but they are also not _preserved_:
Bosk will interpret these as two different nodes and will make no effort to preserve their shared object identity.

To create a reference relationship instead of a containment relationship, use `Reference` or `Listing`.

##### `Catalog`

A `Catalog` is an immutable ordered set of `Entity` objects of a particular type.
A `Catalog` field establishes a one-to-many parent/child containment relationship between nodes.
The identity of each entry is established by `Entity.id()`.

Outside of a state tree, `Catalog` also doubles as a handy immutable collection data structure.
Entities can be added or removed by calling the `with` and `without` methods, respectively.
The `with` operation is an "upsert" operation that replaces an entry if one already exists with a matching ID; otherwise, it adds the new entry to the end.
The `without` operation removes the entry with a given ID, leaving the remaining objects in the same order; if there is no such entry, the operation has no effect.
These operations take O(log n) time.

Because `Catalog` objects _contain_ their entries, the entries can be retrieved or iterated without a `ReadContext`.

##### `Listing`

A `Listing` is an ordered set of references to nodes in a particular `Catalog` referred to as the listing's _domain_.
A `Listing` establishes a one-to-many reference relationship between nodes.

The `Listing` object does not contain its entries; rather, it references entries contained in the domain `Catalog` in the bosk.
This means you need a `ReadContext` to access the referenced objects themselves.

The domain of a `Listing` plays a role similar to a datatype: it indicates the set of possible values to which a `Listing` may refer.

Just as a `Reference` points to a node that may or may not exist,
the entities pointed to by a `Listing` may or may not exist within the domain catalog;
that is, an entry can be added to a `Listing` even if the corresponding `Catalog` entry does not exist.
In fact, the `Catalog` itself need not exist either,
and it's not uncommon for the `domain` of a `Listing` to be a `Phantom` catalog,
in which case the `domain` is even more like a datatype, specifying a set of allowed values in an abstract manner.

Formally speaking, a listing entry carries no information besides its existence.
A `Reference` to a listing entry is of type `Reference<ListingEntry>` and, if the entry exists,
it always has the value `LISTING_ENTRY`. (`ListingEntry` is a unit type.)

##### `SideTable`

A `SideTable` is an ordered map from nodes in a particular `Catalog`, referred to as the `SideTable`'s _domain_,
to some specified type of _value_ node.
A `SideTable` allows you to associate additional information with entities without adding fields to those entities.

The domain of a `SideTable` plays a role similar to a datatype for the `SideTable`'s keys:
it indicates the set of possible values from which the `SideTable`'s keys may be taken.

The `SideTable` object does not contain its keys, but _does_ contain its values.
The keys are contained in the domain `Catalog` in the bosk.
This means you need a `ReadContext` to access the referenced key objects themselves.
Accessing the value objects, on the other hand, does not require a `ReadContext`.

Just as a `Reference` points to a node that may or may not exist,
the keys referenced by a `SideTable` may or may not exist within the `domain` catalog.

##### `Phantom`

A `Phantom` field is a field that does not exist.
It behaves just like an `Optional` field that is always empty.

Phantom fields are primarily useful as the domain for a sparse `Listing` or `SideTable` in situations where there is no useful information to be stored about the key entities.
If you don't already know what this means, you probably don't want to use `Phantom`.

##### `VariantNode`

A _variant node_ is a `StateTreeNode` that behaves like a _sum type_ or _tagged union_.
It's a way of adding polymorphism to the state tree.

To use this feature, declare an interface type that extends `VariantNode`
with one static final field of type `MapValue` that maps tag names
to specific subtypes of the interface.
The field is annotated with `@VariantCaseMap`.
Then implement the `tag()` method to return the tag associated with a given instance
of your variant node; more about this below.

The variant node can be referenced just like any other node,
with a path like `/containingObject/exampleVariant`.
In addition, specific variant cases can be referenced by including the tag in the path,
like `/containingObject/exampleVariant/exampleTag`.
Such a reference is treated as nonexistent if the variant object implements a different tag.
In this way, a variant node behaves like a `StateTreeNode` having `Optional` fields,
one for each variant case, whose name is the tag and whose type is provided by the `@VariantCaseMap`.

It is notable that the variant case map is not entirely determined by annotations,
but is specified by an object that is constructed at runtime.
The intent is that variant nodes offer a way to extend the allowed contents of the bosk at initialization time,
whereas all other bosk contents are determined at build time.

Some bosk components will make an effort to detect tag mismatches,
but to be sure you don't make this mistake,
it is good practice to check for mismatches in your subtype constructors
using code like this:

```java
public record ExampleVariantCase() implements ExampleVariantNode {
	ExampleVariantCase {
		assert ExampleVariantNode.VARIANT_CASE_MAP
			.get(tag())
			.isInstance(this);
	}
}
```

There are typically two ways to implement the `tag()` method.

First, and most straightforward, is to implement it as a `default` method in the interface class,
inspecting the type and possibly fields of `this` and returning the appropriate tag.
This _single method_ approach has the benefit of keeping the `tag()` implementation
near the `@VariantCaseMap` field, which it must match.

Second, if each subtype is associated with only one tag,
then the subtypes can implement `tag()` to return the corresponding tag.
This _polymorphic_ approach lends itself to adding more subtypes over time,
with each new subtype implementing `tag()` as appropriate.
The main `VariantCaseMap` field must still be updated to cover all subtypes,
which can still be a bit of a chore,
but applications could opt to automate this, using SPI or similar, to discover the subtypes.

If a subtype is associated with two or more tags,
the second approach can still be employed by making that subtype's implementation contain
additional logic to determine which tag is appropriate,
potentially by simply adding a `tag` field to the class and returning that.

### Creating `Reference`s

The `Bosk.rootReference()` object acts as a factory for `Reference` objects.
You can call any of the `then*` methods to generate references as desired.
The methods are type-safe, in that they require the caller to pass type information that is checked against what is actually found in the state tree.

The `Bosk` object also offers a method called `buildReferences`
that can create a number of `Reference` objects all at once, in a declarative fashion.
This is usually the preferred way to create references.

To use it, first declare a public interface class with methods annotated with `@ReferencePath`
and returning references of the appropriate type.
A simple example:

```java
public interface Refs {
	@ReferencePath("/")
	Reference<TestRoot> root();

	@ReferencePath("/entities/-entity-")
	Reference<TestEntity> anyEntity();
}
```

The return type of each method must be one of
`Reference`, `CatalogReference`, `ListingReference`, or `SideTableReference`.
The path may be parameterized.

Then instantiate your interface as follows:

``` java
Refs refs = bosk.buildReferences(Refs.class);
```

For parameterized paths, the method may also accept `Identifier` arguments
for one or more of the parameters.
It may also accept `Identifier[]` or `Identifier...` as its last argument.

```java
public interface Refs {
	// Fully parameterized
	@ReferencePath("/planets/-planet-/cities/-city-")
	Reference<City> anyCity();

	// Binds the -planet- parameter and leaves -city- unbound
	@ReferencePath("/planets/-planet-/cities/-city-")
	Reference<City> anyCity(Identifier planet);

	// A concrete reference to a specified city
	@ReferencePath("/planets/-planet-/cities/-city-")
	Reference<City> city(Identifier planet, Identifier city);

	// Varargs
	@ReferencePath("/planets/-planet-/cities/-city-")
	Reference<City> city(Identifier... ids);
}
```

Calling `buildReferences` is costly, requiring perhaps tens of milliseconds,
and performing reflection, class loading, and dynamic bytecode generation.
In contrast, using the resulting object is efficient.
The intent is for `buildReferences` to be called during initialization to build a singleton
for dependency injection.

### Reads

Bosk is designed to provide stable, deterministic, repeatable reads, using the `Reference` class.
`Reference` contains several related methods that provide access to the current state of the tree.

The core of bosk's approach to deterministic, repeatable behaviour is to avoid race conditions by using immutable data structures to represent program state.
To keep the state consistent over the course of an operation, bosk provides _snapshot-at-start_ behaviour:
the same state tree object is used throughout the operation, so that all reads are consistent with each other.

The most commonly used method is `Reference.value()`, which returns the current value of the reference's target node, or throws `NonexistentReferenceException` if the node does not exist.
A referenced node does not _exist_ if any of the reference's path segments don't exist;
for example, a reference to `/planets/tatooine/cities/anchorhead` doesn't exist if there is no planet `tatooine`.

There are a variety of similar methods with slight variations in behaviour.
For example, `Reference.valueIfExists()` is like `value()`, but returns `null` if the node does not exist.

#### Read Context

The `ReadContext` object defines the duration of a single "operation".
Opening a read context captures the bosk state at that moment,
and within a read context, all calls to `Reference.value()` return data taken from that snapshot.

Without a `ReadContext`, a call to `Reference.value()` will throw `IllegalStateException`.
`ReadContext` is an `AutoCloseable` object that uses `ThreadLocal` to establish the state snapshot to be used for the duration of the operation:

``` java
try (var _ = bosk.readContext()) {
	exampleRef.value(); // Returns the value from the snapshot
}
exampleRef.value(); // Throws IllegalStateException
```

By convention, in the bosk library, methods that require an active read context have `value` in their name.

The intent is to create a read context at the start of an operation and hold it open for the duration, so that the state is fixed and unchanging.
For example, if you're using a servlet container, use one read context for the entirety of a single HTTP endpoint method.
Creating many brief read contexts opens your application up to race conditions due to state changes from one context to the next,
and should be done with care.
(If you need to check whether some operation had an effect,
consider using `Bosk.supersedingContext()` instead of many small contexts.)

##### Creation

At any point in the code, a call to `bosk.readContext()` will establish a read context on the calling thread.
If there is already an active read context on the calling thread, the call to `readContext` has no effect.

##### Propagation

Sometimes a program will use multiple threads to perform a single operation, and it is wise to use the same state snapshot for all of them.
A snapshot from one thread can be used on another via `ReadContext.adopt`:

``` java
try (var _ = inheritedContext.adopt()) {
	exampleRef.value(); // Returns the same value as the thread that created inheritedContext
}
```

#### Parameters

A path can contain placeholders, called _parameters_, that can later be bound to `Identifier` values.
A reference whose path contains one or more parameters is referred to as a _parameterized reference_ (or sometimes an _indefinite_ reference);
a reference with no parameters is a _concrete_ (or sometimes _definite_) reference.
Parameters are delimited by a hyphen character `-`, chosen because it survives URL encoding, meaning parameterized paths retain their readability even when URL-encoded.

An example:

``` java
Reference<City> anyCity = bosk.reference(City.class, Path.parseParameterized(
	"/planets/-planet-/cities/-city-"));
```

Parameter values can be supplied either by position or by name.
To supply parameters by position, use `Reference.boundTo`:

``` java
Reference<City> anchorhead = anyCity.boundTo(
	Identifier.from("tatooine"),
	Identifier.from("anchorhead"));
// Concrete reference to /planets/tatooine/cities/anchorhead
```

To supply parameters by name, generate a `BindingEnvironment` and use `Reference.boundBy`. For example, this produces the same concrete reference as the previous `boundTo` example:

``` java
BindingEnvironment env = BindingEnvironment.builder()
	.bind("planet", Identifier.from("tatooine"))
	.bind("city",   Identifier.from("anchorhead"))
	.build();
Reference<City> anchorhead = anyCity.boundBy(env);
```

You can also extract a binding environment using a parameterized reference to do pattern-matching:

``` java
BindingEnvironment env = anyCity.parametersFrom(anchorhead.path()); // binds -planet- and -city-
```

### Updates

The state tree is modified by submitting updates to the bosk's _driver_.
The `BoskDriver` interface accepts updates and causes them to be applied asynchronously to the bosk state.

Because updates are applied asynchronously, it's possible that intervening updates could cause the update to become impossible to apply;
for example, changing a field of an object that has been deleted.
Updates that can't be applied due to the contents of the bosk state are silently ignored.

In contrast, updates that are impossible to apply regardless of the state tree contents will throw an exception at submission time;
examples include an attempt to modify a nonexistent field in an object,
or an attempt to submit an update when an error has left the driver temporarily unable to accept updates.

#### Replacement

The most common form of update is `submitReplacement`, which supplies a new value for a node.
Replacement is an "upsert": the node is left in the desired state whether or not it existed before the update occurred.

An attempt to replace a component of a nonexistent object will be silently ignored;
for example, a replacement operation on `/planets/tatooine/cities` will be ignored if `tatooine` does not exist.[^nonexistent]

[^nonexistent]: It may seem preferable to throw an exception at submission time in such cases.
However, driver implementations are explicitly allowed to queue updates and apply them later,
because queueing is often a key strategy to achieve robust, scalable distributed systems.
Requiring synchronous confirmation about the current state of the bosk rules out queueing.
By requiring these operations to be ignored, bosk ensures the behaviour is the same in local development and in production,
and so any confusion caused by this behaviour should be encountered early on in the application development process.

#### Deletion

Some nodes in the tree can be deleted.
Examples include:
- Fields of type `Optional`
- Entries in a `Catalog`, `Listing` or `SideTable`

To delete such nodes, call `BoskDriver.submitDeletion`.
When applied, a deletion causes the node (and all its children) to become nonexistent.
The semantic nuances are similar to those of replacement.

#### Conditional updates

The replacement and deletion operations each have corresponding _conditional_ forms.
Conditional updates are silently ignored if a given `precondition` node does not have the specified `requiredValue` at the time the update is to be applied.
For example, `submitConditionalReplacement(target, newValue, precondition, requiredValue)` has the same effect as
`submitReplacement(target, newValue)`, unless the node referenced by `precondition` has a value other than `requiredValue` or does not exist.

`submitConditionalDeletion` is similar.

A third kind of conditional update, called `submitConditionalCreation`, is like `submitReplacement` except ignored if the target node already exists.

#### `flush()`

The `flush()` method ensures all prior updates to the bosk have been applied,
meaning they will be reflected in a subsequent read context.

Formally, the definition of "prior updates" is the _happens-before_ relationship from the Java specification.
Conceptually, `flush` behaves as though it performs a "nonce" update to the bosk and then waits for that update to be applied;
the actual implementation may, of course, operate differently.
Even in parallel distributed setups with queueing, bosk updates are totally-ordered
(like _synchronizing operations_ from the Java spec),
so waiting for the "nonce" update ensures all prior updates have also been applied.

The semantics are such that the following example works correctly.
A bosk-based application is deployed as a replica set, with multiple servers sharing a single bosk (eg. using `MongoDriver`).
A client makes a request to the first server to update the bosk,
and then makes a request to the second server to call `flush()` and then read from the bosk.
In this scenario, the second request is guaranteed to reflect the update applied by the first request,
even though they are executed by different servers.

Calling `flush()` inside a read context will still apply the updates,
but those changes will not be reflected by any reads performed in the same read context,
since the read context continues using the state snapshot acquired when the read context began.

`Flush` does not guarantee that any hooks triggered by the applied updates will have been called yet.
To wait for a particular hook to run, the hook and application code must cooperate using a synchronization mechanism such as a semaphore.
(Be aware, though, that hooks can be called more than once, so make sure your semaphore code can cope with this case.)

#### Conformance rules

`BoskDriver` implementations typically take the form of a stackable layer that accepts update requests, performs some sort of processing, and forwards the (possibly modified) requests to the next driver in the chain (the _downstream_) driver.
This is a powerful technique to add functionality to a Bosk instance.

To retain compatibility with application code, however, driver implementations should obey the `BoskDriver` contract.
The low-level details of that contract are well documented in the `BoskDriver` javadocs, and are tested in the `DriverConformanceTest` class.
In addition, there there are also important higher-level rules governing the allowed differences between the updates a driver receives and those it forwards to the downstream driver.
Breaking these rules might alter application behaviour in ways that the developers won't be expecting.

Broadly, the validity of a sequence of updates can be understood in terms of the implied _sequence of states_ that exist between updates.
The updates emitted downstream by a driver layer are allowed to differ from the operations it received,
provided that the emitted updates have the same effect on the bosk state.
For example, if the layer receives a conditional update whose precondition matches, it is allowed to submit an equivalent unconditional update downstream.
Another example: if the layer receives an update that has no effect on the state, it is allowed to ignore that update and decline to submit it downstream.
These rules are checked during the `DriverConformanceTest` suite via the `DriverStateVerifier` class.

Taking advantage of these state-based rules require that the driver maintains an awareness of the current bosk state,
which _most drivers do not_,
and so most drivers are rarely able to exploit the flexibility provided
because they can't generally determine what effect an update will have.
However, for drivers that do track the bosk state,
these rules allow flexibility that might otherwise make driver implementations tricky to write if they were required to emit the exact same sequence of updates they received;
for instance, it means a queue system does not require exactly-once message delivery because
a duplicated message is ok as long as messages arrive in order.

### Hooks

The `Bosk.registerHook` method indicates that a particular call-back should occur any time a specified part of the state tree (the hook's _scope_) is updated.

``` java
bosk.registerHook("Name update", bosk.nameRef, ref -> {
	System.out.println("Name is now: " + ref.value());
});
```

Hooks are also called at registration time for all matching nodes.

Hooks can also fire spontaneously; any application logic in a hook must be designed to accept additional calls even if the tree state didn't change.
In particular, updating a counter from a hook might lead to over-counting,
as could acquiring or releasing a semaphore.

A hook's scope can be a parameterized reference, in which case it will be called any time _any_ matching node is updated.
Suppose your bosk has a field declared as follows:

``` java
final Reference<ExampleWidget> anyWidget = reference(ExampleWidget.class, Path.parseParameterized(
	"/widgets/-widget-"));
```

You can then declare a hook as follows:

``` java
bosk.registerHook("Widget changed", bosk.anyWidget, ref -> {
	System.out.println("A widget changed: " + ref); // `ref` points to the particular widget that changed
});
```

The hook call-back occurs inside a read context containing a state snapshot taken immediately after the triggering update occurred.
This means there are no race conditions between bosk reads in a hook versus other bosk updates happening in parallel.

If a single update triggers multiple hooks, the hooks will run in the order they were registered.

#### Breadth-first ordering

It is fairly common for hooks to perform bosk updates, and these could themselves trigger additional hooks.
Triggered hooks are queued, and are run in the order they were queued.

For example, if one update triggers two hooks A and B, and then A performs an update that triggers hook C,
B will run before C. The hooks will reliably run in the order A, B, C.
When C runs, its read context will reflect the updates performed by A and C but not B, _even though B ran first_[^ordering].

See the `HooksTest` unit test for examples to illustrate the behaviour.

[^ordering]: It might at first appear strange that hook C would not observe the effects of hook B, if B runs before C.
However, recall that, though B's updates will be _submitted_ before C runs, there is no guarantee that they will be _applied_ before C runs.
Suppose, for example, that we've chosen to deploy our application as a cluster that uses a queueing system
(perhaps for scalability, or change data capture, or any number of other reasons that distributed systems might use a queue).
This would cause a delay between when B _submits_ the update and when the bosk _applies_ the update.
Rather than expose users to a race condition in some operating environments that is not present in others,
bosk heavily favours consistency, and employs a convention that can be implemented efficiently in many environments:
updates from B are never visible in C's read scope.
Whatever confusion this might cause, that confusion will be encountered during initial application development,
rather than providing surprises when moving to a different environment for production.

#### Exception handling

Any `Exception` thrown by a hook is caught, logged, and ignored.
This makes the hook execution loop robust against most bugs in hooks.

`Error`s are not ignored.
In particular, `AssertionError` is not ignored, which allows you to write unit tests that include assertions inside hooks.

### Drivers

`BoskDriver` defines the interface by which updates are sent to a bosk.

The interface's update semantics are described in the _Updates_ section above.
This section focuses on the configuration and implementation of drivers, rather than their usage,
and briefly describes the drivers that are built into the bosk library.

#### Local driver

Every bosk has a _local driver_, which applies changes directly to the in-memory state tree.
If you use `Bosk.simpleDriver()` as your driver factory when you initialize your `Bosk` object,
then the driver is _just_ the local driver.

The local driver performs the grafting operations that create a new state tree containing specified changes applied to the existing tree.
The local driver is also the component responsible for triggering and executing hooks.

Despite the `BoskDriver` interface's asynchronous design, the local driver actually operates synchronously, and does not use a background thread.
The calling thread is used to trigger hooks, and even to run them (unless a hook is already running on another thread).
This makes basic in-memory updates efficient and easily debuggable.

Parallel updates from multiple threads, or recursive updates from bosk hooks,
are handled using an algorithm we call "reactive breadth-first search"
that still performs all updates on one of the application threads,
coordinated using just `Semaphore`.
In this case, updates are still highly efficient,
though debuggability is a bit more challenging because updates from one thread may be applied by another.


#### DriverStack and DriverFactory

`BoskDriver` itself is designed to permit stackable layers (the _Decorator_ design pattern),
making drivers modular and composable.

The simplest `DriverFactory` is `Bosk.simpleDriver()`, which just uses the bosk's local driver, which directly updates the Bosk's in-memory state tree.
More sophisticated driver layers can provide their own factories, which typically create an instance of the driver layer object configured to forward update requests to the downstream driver, forming a forwarding chain that ultimately ends with the bosk's local driver.

For example, an application could create a `LoggingDriver` class to perform logging of update requests before forwarding them to a downstream driver that actually applies them to the bosk state.

The `DriverFactory` interface is used to instantiate a driver layer, given the downstream driver object:

``` java
public interface DriverFactory<R extends Entity> {
	BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> downstream);
}
```

The `DriverStack` class facilitates the composition of driver layers.
`DriverStack` extends `DriverFactory`; that is, a `DriverStack` is a kind of `DriverFactory` that invokes other factories to assemble a composite driver.

For example, a stack could be composed as follows:

``` java
DriverFactory<ExampleState> exampleDriverFactory() {
	return DriverStack.of(
		LoggingDriver.factory("Submitted to MongoDriver"),
		MongoDriver.factory(...)
	);
}
```

This creates a chain configured to process each update as follows:
1. The `LoggingDriver` will log the event, and forward it to the `MongoDriver`
2. The `MongoDriver` will send the update to MongoDB, and then receive a change event and forward it to the bosk's local driver
3. The local driver will update the in-memory state tree

(The local driver doesn't appear in a `DriverStack`. It is implicitly at the bottom of every stack.)

Later on, this could even be extended by sandwiching the `MongoDriver` between two `LoggingDriver` instances,
in order to log events submitted to and received from `MongoDriver`:

``` java
DriverFactory<ExampleState> exampleDriverFactory() {
	return DriverStack.of(
		LoggingDriver.factory("Submitted to MongoDriver"),
		MongoDriver.factory(...),
		LoggingDriver.factory("Received from MongoDriver") // NEW LAYER!
	);
}
```

The `DriverFactory` and `DriverStack` classes make this a one-line change.

All of this might appear a bit abstract, but the upshot is that your drivers can snap together like Lego.

#### Built-in drivers

Some handy drivers ship with the `bosk-core` module.
This can be useful in composing your own drivers, and in unit tests.

- `BufferingDriver` queues all updates, and applies them only when `flush()` is called.
- `ForwardingDriver` simply forwards updates to a downstream driver; subclasses can override the update methods to add additional functionality.
- `ReplicaSet` allows bosks to join a group of bosks such that updates to any of the bosks are replicated to all the others.
- `MongoDriver` enables persistence and replication, and is important enough that it deserves its own section.

#### `MongoDriver` and `bosk-mongo`

By adding the `bosk-mongo` dependency to your project and configuring `MongoDriver`,
you can turn your server into a replica set with relatively little difficulty.

`MongoDriver` uses MongoDB as a broadcast medium to deliver bosk updates to all the servers in your replica set.
Newly booted servers connect to the database, initialize their bosk from the current database contents, and follow the MongoDB change stream to receive updates.

##### Configuration and usage

Like most drivers, `MongoDriver` is not instantiated directly, but instead provides a `DriverFactory` to simplify composition with other driver components.
Create a `MongoDriverFactory` by calling `MongoDriver.factory`:

``` java
static <RR extends Entity> MongoDriverFactory<RR> factory(
	MongoClientSettings clientSettings,
	MongoDriverSettings driverSettings,
	BsonSerializer bsonSerializer
) { ... }
```

The arguments are as follows:

- `clientSettings` is how the MongoDB client library configures the database connection.
- `driverSettings` contains the bosk-specific settings, the most important of which is `database` (the name of the database in which the bosk state is to be stored). Bosks that use the same database will share the same state.
- `bsonSerializer` controls the translation between BSON objects and the application's state tree node objects. For simple scenarios, the application won't need to worry about this object, and can simply instantiate one and pass it in.

Here is an example of a method that would return a fully configured `MongoDriverFactory`:

``` java
static DriverFactory<ExampleState> driverFactory() {
	MongoClientSettings clientSettings = MongoClientSettings.builder()
		.build();

	MongoDriverSettings driverSettings = MongoDriverSettings.builder()
		.database("ExampleBoskDB") // Bosks using the same name here will share state
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

##### Database setup

Bosk supports MongoDB 4.4 and up.

To support change streams, MongoDB must be deployed as a replica set.
In production, this is a good practice anyway, so this requirement shouldn't cause any hardship:
the MongoDB documentation recommends against deploying a standalone server to production.

For local development, standalone MongoDB servers don't support change streams (for some reason).
To support `MongoDriver`, you must use a replica set, even if you are running just one server.
This can be achieved using the following `Dockerfile`:

``` dockerfile
FROM mongo:4.4 # ...but use a newer version if you can
RUN echo "rs.initiate()" > /docker-entrypoint-initdb.d/rs-initiate.js
CMD [ "mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all" ]
```

##### Robustness and Serviceability

An important design principle of `MongoDriver` is that it should be able to recover from temporary outages without requiring an application reboot.
When faced with a situation it can't cope with, `MongoDriver` has just one fallback mode of operation: a _disconnected_ state that does not process changes from the database.
Once disconnected, `MongoDriver` will no longer send updates downstream, and so the in-memory state will stay frozen until the connection can be re-established.

Recovering from a disconnected state occurs automatically when conditions improve, and should not require any explicit action to be taken.
Also, no particular sequence of steps should be required to recover: any actions that an operator takes to restore the database state and connectivity should have the expected effect.

For example, suppose the bosk database were to be deleted.
`MongoDriver` would respond by suspending updates, and leaving the last known good state intact in memory.
Perhaps the operator takes the database offline entirely, then reboots it and restores the last known good state from a backup.
`MongoDriver` would respond by reconnecting to the database (possibly after some period of time) and reloading the database state to re-sync the in-memory state with the database.

##### Logging

Like the rest of bosk, the `bosk-mongo` module logs via the SLF4J framework.
The logs emitted assume that applications will use log level `WARN` for bosk.
Additional detail is available at higher logging levels:
`INFO` will contain details likely to be useful for bosk users, while
`DEBUG` is more likely to be useful for the maintainers of the bosk library.
(`TRACE` can produce a large amount of output and isn't generally recommended for production.)

The logs make use of the Mapped Diagnostic Context (MDC) feature of SLF4J to provide
several MDC keys with `MongoDriver` as a prefix.
If you might find this useful, you can configure your logging system to emit this key;
for example, using Logback, you can add `%X{MongoDriver}` to your appender's encoder pattern.
When present, the string associated with the `MongoDriver` MDC key always starts with a single space character,
so you can append it to your existing log strings with no whitespace,
meaning it takes up no space at all when it's not present.

##### Database format & layout

`MongoDriver` offers two database format options:
- The _Sequoia_ format (the default) stores the entire bosk state in a single document in a single collection.
- The _Pando_ format divides up the bosk state into multiple documents, to overcome the MongoDB limit of 16MB for a single document.

For Sequoia, the collection is called `boskCollection` and the document has four fields:

- `_id`: this is always `boskDocument`
- `path`: this is always `/`
- `state`: contains the entire bosk state tree
- `revision`: an incrementing version number; used to implement `flush()`

The format of the `state` field is determined by `BsonSerializer` and `Formatter`.
The code will have the details, but some high-level points about the BSON format:

- It does not match the JSON format generated by `bosk-jackson`. This is a deliberate decision based on differing requirements.
- It strongly favours objects over arrays, because object members offer efficient idempotency and (ironically) stronger ordering guarantees.

For Pando, the situation is similar, except that instead of having a single document with an `_id` of `boskDocument`,
there are multiple documents with `_id` values that start with `|` (vertical bar)
and that describe where the document fits within the overall BSON structure.

##### Schema evolution: how to add a new field

In general, bosk does not support `null` field values.
If you add a new field to your state tree node classes, they become incompatible with the existing database contents (which do not have that field).
This means that new fields must, at least initially, support being absent.

The first step is to use the `@Polyfill` annotation to indicate a default value:

``` java
record ExampleNode(ExampleValue newField) {
	@Polyfill("newField")
	static final ExampleValue NEW_FIELD_DEFAULT = ExampleValue.DEFAULT_VALUE;
}
```

This will allow operations that deserialize `ExampleNode` objects (from JSON, from databases, etc.)
to tolerate the absence of `newField` temporarily by providing the given default value.
With the `@Polyfill` in place, any updates written to MongoDB will include the new field,
so the database state will be gradually upgraded to include the new field.
Because `MongoDriver` ignores any fields in the database it doesn't recognize,
this new version of the code can coexist with older versions that don't know about the new field.

The second step is to ensure that any older versions of the server are shut down.
This will prevent _new_ objects from being created without the new field.

The third step is to change external systems so they always supply the new field;
for `MongoDriver`, this is accomplished by calling `MongoDriver.refurbish()`.[^refurbish]
This method rewrites the entire bosk state in the new format, which has the effect of adding the new field to all existing objects.

Finally, you can remove the `@Polyfill` field,
secure in the knowledge that there are no objects in the database that don't have the new field.

Note that `@Polyfill` is not meant as a general way to supply default values for optional fields,
but rather to allow rollout of new required fields with no downtime.
For optional fields, just use `Optional`.

[^refurbish]: Note that if your database is using the Sequoia format, and you refurbish it to the Pando format,
there is a brief window (before the change events arrive) when writes to the old Sequoia driver will
be silently ignored. While refurbishing from Sequoia to a different format,
ensure the bosk is quiescent (not performing any updates), or is performing a `flush()` before each update.
This is a consequence of Sequoia's design simplicity; specifically, its avoidance of multi-document transactions.

### Serialization: `bosk-jackson`

The `bosk-jackson` module uses the Jackson library to support JSON serialization and deserialization.

#### Configuring Jackson

To configure an `ObjectMapper` that is compatible with a particular `Bosk` object, use the `JacksonSerializer.moduleFor` method.
Here is an example:

``` java
JacksonSerializer jacksonSerializer = new JacksonSerializer();
boskMapper = new ObjectMapper()
	.registerModule(jacksonSerializer.moduleFor(bosk))

	// You can add whatever configuration suits your application:
	.enable(INDENT_OUTPUT);
```

`JacksonSerializer` is compatible with many of the `ObjectMapper` configuration options, so you should be able to configure it as you want.

#### JSON format

Most nodes are serialized in the expected fashion, with one member per field,
and child objects nested inside parents.

The format of the various built-in types is shown below.

``` json5
"reference": "/a/b/c",      // References are strings
"catalog": [                // Catalogs are arrays of single-member objects
    {
        "entry1": {
            "id": "entry1", // The id field is included here (redundantly)
            "exampleField": "value"
        }
    }
],
"listing": {                // Listings are objects with two fields
    "ids": ["entry1", "entry2"],
    "domain": "/catalog"    // Reference to the containing Catalog
},
"sideTable": {              // SideTables are objects with two fields
    "valuesById": [
        { "entry1": { "exampleField": "value" } },
        { "entry2": { "exampleField": "value" } }
    ],
    "domain": "/catalog"    // Reference to the containing Catalog
},
"variantNode": {
    "exampleTag": {         // Indicates which variant case this object implements
        /*
        Fields for subtype associated with exampleTag go here.
        */
    }
}
```

A field of type `Optional<T>` is simply serialized as a `T`, unless the optional is empty, in which case the field does not appear at all.

A field of type `Phantom<T>` is not serialized (just like `Optional.empty()`).

When supplying JSON for deserialization,
the `id` field of a `Catalog` entry or a `SideTable` key may be omitted,
and will be inferred during deserialization if possible from context,
including any `@DeserializationPath` annotations.
This inference process takes some time, though,
so for best performance, it's better for the JSON input to include the `id` field,
just as it does when serialized.

#### DeserializationScope

Fields marked as `@Self` or `@Enclosing` are not serialized.
They are inferred automatically at deserialization time.

In order to infer the correct values of `@Self` and `@Enclosing` references,
the deserialization process must keep track of the current location in the state tree.
This is simple when deserializing the entire bosk state: the location starts in the root object,
and from there, the format is designed in such a way that the location can be tracked as JSON parsing proceeds.

However, when deserializing only part of the bosk state (which is by far the most common situation),
the deserialization must know the corresponding state tree location so it can compute `@Self` and `@Enclosing` references.

To deserialize just one node of the bosk state, use a try-with-resources statement to wrap the deserialization in a `DeserializationScope` object initialized with the path of the node being deserialized:

``` java
try (var _ = jacksonSerializer.newDeserializationScope(ref)) {
	newValue = objectMapper.readValue(exampleJson, ref.targetType());
}
```

For this to work, you will need access to the `JacksonSerializer` object,
typically from your dependency injection framework.

For an object whose _fields_ represent specific nodes of the bosk state,
use the `@DeserializationPath` annotation; see the javadocs for more info.

### Recommendations

#### Create a subclass of `Bosk` and create references at startup

References are designed to be created once and reused many times.
Occasionally, you can create references dynamically, but it will be slower, and usually there's no need.

A typical pattern is to create a `Bosk` subclass containing a long list of references your application needs.
Larger apps might want to break up this list and put references into separate classes,
but small apps can dump them all into the `Bosk` object itself.

As a naming convention, indefinite references (with parameters) start with `any`,
unless the method accepts enough arguments to bind all the parameters.

Example:

``` java
import works.bosk.Bosk;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import annotations.works.bosk.ReferencePath;
import exceptions.works.bosk.InvalidTypeException;

@Singleton // You can use your framework's dependency injection for this
public class ExampleBosk extends Bosk<ExampleState> {
	public final Refs refs;

	public ExampleBosk() throws InvalidTypeException {
		super(
			"ExampleBosk",
			ExampleState.class,
			new ExampleState(Identifier.from("example"), "world"),
			driverFactory());
		this.refs = buildReferences(Refs.class);
	}

	public interface Refs {
		@ReferencePath("/name") Reference<String> name();
		@ReferencePath("/widgets") CatalogReference<ExampleWidget> widgets();
		@ReferencePath("/widgets/-widget-") Reference<ExampleWidget> anyWidget();
		@ReferencePath("/widgets/-widget-") Reference<ExampleWidget> widget(Identifier widget);
	}

	// Start off simple
	private static DriverFactory<ExampleState> driverFactory() {
		return Bosk.simpleDriver();
	}
}
```

#### Services, tenants, catalogs

To reduce coupling between different parts of a large codebase sharing a single bosk,
the fields of the root node are typically different "services" owned by different development teams.
The next level would be a `Catalog` of tenants or users, depending on your application's tenancy pattern.
Finally, within a tenant node, many of the important objects are stored in top-level catalogs,
rather than existing only deeper in the tree.

For example, a typical bosk path might look like `/exampleService/tenants/-tenant-/exampleWidgets/-widget-`.

#### Arrange state by who modifies it

There is a tendency to place all state relevant to some object _inside that object_.
Bosk encourages you to separate state that is modified by different parts of the code,
employing `SideTable`s rather than putting all state in the same object.

For example, suppose your application distributes shards of data to worker nodes in a cluster.
You could imagine a `Worker` object like this:

``` java
// Not recommended

public record Worker (
	Identifier id,
	String baseURL,
	Status status,
	Catalog<Shard> assignedShards
) {}
```

 **Don't do this**. The trouble is, this puts state into the same object that is changed under three different circumstances:
- `baseURL` is set by static configuration or by service discovery. This is _configuration_: information supplied to your application to tell it how to behave.
- `status` is set either by a polling mechanism, or when worker communications result in an error. This is an _observation_: information your application draws from external systems.
- `assignedShards` is set by the data distribution algorithm. This is a _decision_: a choice made by your application, typically in response to _configuration_ and _observations_.

You want to separate configuration from observations from decisions.
The entity itself should contain only configuration; observations and decisions should be stored in `SideTable`s.

A better arrangement of this state might look like this:

``` java
// Recommended

public record Worker (
	Identifier id,
	String baseURL
) {}

public record Cluster (
	Catalog<Worker> workers,
	SideTable<Worker, Status> workerStatus,
	SideTable<Worker, Shard> workerAssignments
) {}
```

#### Use large read contexts

Using a succession of multiple `ReadContext`s for the same operation causes that operation to be exposed to race conditions from concurrent state updates.

For any one operation, use a single `ReadContext` around the whole operation.
The "operation" should be as coarse-grained as feasible.

Some examples:
- An HTTP endpoint method should be enclosed in a single `ReadContext`. Typically this is done by installing a servlet filter that acquires a `ReadContext` around any `GET`, `HEAD`, or `POST` request (assuming you use `POST` as "`GET` with a body". If you use RPC-style `POST` endpoints, you might not be able to have a single `ReadContext` around the entire endpoint.) Note that `PUT` and `DELETE` typically don't need a `ReadContext` at all.
- A scheduled action (eg. using the `@Scheduled` annotation in Spring Boot) should immediately acquire a `ReadContext` for its entire duration

In general, open one large `ReadContext` as early as possible in your application's call stack unless this is unworkable for some reason.

#### Closed-loop control hooks

Bosk is often used to control a server's _local state_.
For example, a caching application could use bosk to control what's in the cache in the server's memory,
so that all servers have the same cache contents and therefore provide reliable response times across the cluster.
The cache itself is _local state_ because it exists independently in each server instance.

To make your system declarative and idempotent,
write your hooks in a style that follows these steps:

1. From the current bosk state, compute the desired local state
2. Compare the desired state with the actual local state
3. If they differ, make changes to the local state to make it match the desired state

This style leads to more stable systems than imperative-style hooks that respond to bosk updates by issuing arbitrary imperative commands.

#### Avoid recursive data structures

Having a node of some type contain a descendant node of the same type is usually a code smell in a Bosk state tree.
Recursive structures require the application to create an unlimited number of `Reference`s dynamically
(for example, `/root/child`, `/root/child/child`, `/root/child/child/child` and so on),
which is awkward and slow.
It also makes it difficult to evolve your design if you discover you need to handle a use case in which the relationship is not strictly a tree.

For example, if you are representing information about files and folders in your bosk,
one natural design would be to nest child folders inside parent folders,
and make the files children of the folder they are in.
 **Don't do this.**

Instead, create two top-level `Catalog`s: one for `File`s and one for `Folder`s.
Represent their nesting relationships using `Reference`s.
This way, two parameterized references can access all your objects: `/files/-file-` and `/folders/-folder-`.
In addition, if you discover you need to handle hard links, where the same file is in multiple folders, this becomes a straightforward extension instead of an awkward redesign.

### Glossary

_Apply_: When an update has been _applied_ to the bosk, it will be reflected in a subsequent read context

_Driver_: An object that accepts and processes bosk updates

_Entity_: A state tree node with an `id` field, which can participate in certain bosk features.
Catalog entries must be entities, for example.

_Node_: An object in the state tree.

_Path_: The sequence of fields that reaches a particular state tree node starting from the tree's root node.

_Parameter_: A path segment that can be substituted for an `Identifier`.

_Polyfill_: A default value for a missing field. Simplifies backward compatibility with older databases or serialized data, allowing application code to assume a field is present even if it's not.

_Reference_: A type-safe representation of a `Path` that can be used to access a node in a particular `Bosk` object.

_Root_: The topmost, or outermost, state object in a bosk.

_Scope_: (of a hook) a reference to the node (or nodes, if the scope is parameterized) being watched for changes. Any updates to that node will cause the hook to be triggered.

_Segment_: A portion of a path between slashes. The path `/a/b/c` has three segments: `a`, `b`, and `c`. In its string representation, the segments of a path are URL-encoded.

_Submit_: (of an update) to be sent to the driver for subsequent execution.

_Trigger_: (of a hook) to be queued for execution. A hook is triggered whenever its scope node is updated. The execution may happen immediately, or it may happen later, depending on the circumstances.
