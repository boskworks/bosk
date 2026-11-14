
## Design principles

### _Sequoia_ and _Pando_ formats

The existing format is called _Sequoia_, and stores the entire state tree in one big document.
Aside from `refurbish`, it doesn't need multi-document transactions.

The multi-document format is called _Pando_, and stores the state tree using one "root" document,
plus a number of separate documents corresponding to nodes that are entries in particular
`Catalog` or `SideTable` nodes configured to be stored separately.

### Manifest is the system of record for layout

A `MongoDriver` will accept a configuration describing the desired database layout.
However, if the database already exists, this is ignored, and the layout from the `manifest` document is used instead.
(In this way, the layout acts like the `initialState`.)

Any change to the `manifest` document probably triggers the bosk to be reloaded
the same way as for `initialState`.

### Scattering and gathering are done by mutating Bson objects

A single bosk update can correspond to multiple database writes,
and conversely, multiple change events can correspond to a single bosk update.
We refer to these as "scattering" and "gathering", respectively.

Scattering is done in these steps:
1. Serialize the bosk state into Bson as usual
2. Mutate the Bson to separate it into multiple Bsons based on the database layout
3. Write the objects in bottom-up order

Gathering is done in these steps:
1. Receive all the change events and buffer them, bottom-up, ending with the top Bson object
2. Mutate the top object to include all the other objects
3. Deserialize the Bson into bosk state as usual

### Limitations

There are some decisions we can make initially that will simplify the implementation.
We might find ways to overcome these limitations in the future.

#### Deleted nodes can be left behind

Suppose a Catalog is configured to use a separate collection, and it contains objects `A` and `B`.
Then an update arrives to replace that with `A` and `C`.
The driver is allowed to leave the `B` document intact.

Dangling documents can be cleared via `refurbish()` if desired.

An explicit `submitDeletion` shouldn't leave the target document behind,
but is allowed to, if it finds some other way to communicate the deletion via change streams.

## Database contents

A Pando-formatted database, like a Sequoia-formatted one, stores the bosk state in a single collection.
The collection is named `boskCollection` by default, and can be renamed with `MongoDriverSettings.collection()`.

#### Document `manifest`

Fields:
- `version`: a "major version" integer that is incremented whenever there are breaking changes to
the conventions for the manifest document itself
- `formatId`: identifies this instance of the format; every state document carries the same value
- Variants: only one of the following fields is permitted:
- `sequoia`: an empty document signifying that the database contents are stored in accordance with the Sequoia format
- `pando`: a document containing a `graftPoints` field which describes how the state tree is to be carved up into documents

#### Document `|`

This is the root document.

Fields:

- `_id`: always `|`
- `state`: the bosk state
- `formatId`: matches the manifest's
- Any graft points (Catalogs or SideTables) map IDs to the value `true` (rather than containing the actual tree node)
- `epoch`: identifies the collection initialization; used to detect a collection that has been deleted and re-created
- `incarnation`, `revision`: the root document's revision number and incarnation (see below)
- `diagnostics`: the diagnostic attributes from the most recent update for which this document was topmost

#### Sub-part documents

- `_id`: a pipe-delimited BSON path to the location of this document's state within the overall BSON structure
- `state`: the state of the particular node represented by this document
- `formatId`: matches the manifest's
- `incarnation`, `revision`: the document's revision number and incarnation

Often, the BSON path for a particular state tree node looks like its bosk path with slashes replaced by pipe characters.
They will differ for nodes under `SideTable` nodes,
because the BSON representation for those nodes adds an additional field `valuesById`.

Replacing a whole entry rewrites each of its sub-parts, whether or not the
sub-part's state changed, because the change events for those documents are
what let the receiver gather the new value. Each sub-part is written with a
single upserting replace, which produces an `INSERT` or a `REPLACE` carrying
the full document.

#### Revision numbers and incarnations

Every state document carries `incarnation` and `revision` fields.
Only the **topmost** document changed by an update has its `revision` incremented:
that is, the shallowest document whose `state` field changes.
This means unrelated updates to different graft documents do not conflict on a shared document.

A document created for the first time has a fresh incarnation (a UUID) and revision 1.
When a document is deleted and re-created, it gets another fresh incarnation and
its revision resets to 1. Incarnations are identities, not ordered values, so a
re-created document's revision 1 is not mistaken for an old revision 1.
A flush target is satisfied when the applied incarnation differs, which means the
target belongs to a superseded lifetime, or when both share an incarnation and
the applied revision is at least the target revision. A flush waits for
ancestors before descendants, so the incarnation change has been applied before a
descendant's incarnation is compared.

Replacing a whole entry that a graft point stores separately is an update of the
entry's document: the entry keeps its incarnation, its revision is incremented,
and the container is not written, so the entry's document is the topmost document
of the transaction. If the container has no stub for the entry, the entry is
being created: its document gets a fresh incarnation and revision 1, and the
container's stub is added as the transaction's topmost write. Deleting an entry
leaves its document behind but removes the container's stub (see
[Deleted nodes can be left behind](#deleted-nodes-can-be-left-behind)), so the
presence of the document alone does not mean the entry exists.

`flush()` reads every document's revision number and incarnation in bottom-up
order (descendants before ancestors) using `ReadConcern.LOCAL`; it is read-only.
It then waits for them in top-down order (ancestors before descendants).
Waiting for each document's revision means a flush observes changes to graft
documents that do not touch the root.
