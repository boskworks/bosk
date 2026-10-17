# Design notes

This document records design directions and deferred ideas that are worth remembering
but are not yet part of the design or the roadmap.
It is deliberately informal: the point is to capture the idea and its motivation
so we can pick it up later, not to specify it in full.

## BoskDriver: designing the interface around queueing

We've long had a vague idea that `BoskDriver` should be designed to support queueing.
In that design, submitting an update would not report synchronous success;
the only failure reported at submission time would be one that is doomed regardless
of the database state, such as an update that is malformed for the state tree layout.
This is consistent with the contract as it already stands:
updates are applied asynchronously and may be silently ignored
when the state tree makes them impossible to apply.
See the [User's Guide](USERS.md#replacement) for the asynchronous update semantics,
and the footnote explaining why drivers are allowed to queue updates.

A newly apparent motivation for this is **batching**.
When a database is overloaded, one effective remedy is to batch updates,
which amortizes the fixed cost of each update (network round trips, transactions,
index maintenance, and so on) and so makes each update cheaper for the database.
Queueing and batching are the same mechanism serving different ends:
a driver that queues can accumulate submissions and hand them to the database as one batch,
which bounds the load an application places on the database even when submissions arrive faster
than the database can apply them.

### Outcome: MongoDB already batches the expensive work

We explored a batching layer inside `MongoDriver`
(accumulate concurrent submissions for a short window, then write them together)
and decided not to build it.
This is a decision about the batching layer specifically,
not about the queueing model for the `BoskDriver` interface above.

MongoDB already groups the expensive part of concurrent writes.
WiredTiger ["automatically groups the flush operations for threads that commit concurrently
into single calls"](https://source.wiredtiger.com/develop/tune_durability.html),
so the journal-flush (durability) cost is amortized across concurrent writers
without any help from us.
Note that this batches the _flush_, not the _command_:
separate `updateOne` commands from a client are still separate round trips and separate oplog entries,
and merging those would be `bulkWrite`.

Change streams are already batched on the read side.
A change stream is a tailable-await cursor over the oplog,
and the server returns pending events in batches per `getMore`
(up to the batch size or 16MB), with `maxAwaitTimeMS` bounding how long the server waits for more.
`ChangeReceiver` reads the events one at a time, but the network fetch is already amortized;
only the per-event local processing remains.

There was also a correctness constraint that made the layer less attractive than it first looked.
A `submit` that has returned must be durable,
so a submitter can only be released after the batch containing its update has committed;
each thread therefore has at most one update in flight,
and the batch size is bounded by the number of concurrently submitting threads.
A time-only window then caps throughput at `threads / window`,
so a real implementation would also need a size trigger
(like Kafka's `linger.ms` together with `batch.size`)
so that the window degrades to a latency bound under light load rather than a throughput limit under heavy load.

The remaining benefit would be fewer update commands and hence fewer change events to process.
If that ever becomes the bottleneck,
merge concurrent updates into a `bulkWrite`
rather than building a full scheduling layer.
