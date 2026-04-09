## Refactoring Plan: Consolidate Logic into `AbstractFormatDriver`

---

### Step 1: Lift `logNonexistentField` and `ALREADY_WARNED`

In `AbstractFormatDriver`, add:
```java
private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());

protected void logNonexistentField(String dottedName, InvalidTypeException e) {
    LOGGER.trace("Nonexistent field {}", dottedName, e);
    if (LOGGER.isWarnEnabled() && ALREADY_WARNED.add(dottedName)) {
        LOGGER.warn("Ignoring updates of nonexistent field {}", dottedName);
    }
}

private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFormatDriver.class);
```

Add imports to `AbstractFormatDriver`:
- `java.util.Set`
- `java.util.concurrent.ConcurrentHashMap`
- `static java.util.Collections.newSetFromMap`
- `works.bosk.exceptions.InvalidTypeException`
- `org.slf4j.Logger`
- `org.slf4j.LoggerFactory`

In both subclasses, delete:
- `logNonexistentField` method
- `ALREADY_WARNED` field
- Any imports that are now unused

**Verify:** Both subclasses call `logNonexistentField(dottedName, e)` with the same signature — no call-site changes needed.

---

### Step 2: Lift `replacementDoc` and `deletionDoc`

Both subclasses have these methods. The key difference is that Sequoia's `replacementDoc` accepts a domain object `T newValue` and serializes it internally, while Pando's accepts an already-serialized `BsonValue`. The unified version should accept `BsonValue`, matching Pando's signature, so Sequoia's call sites need to serialize before calling.

In `AbstractFormatDriver`, add:
```java
protected <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value, Reference<?> startingRef) {
    String key = dottedFieldNameOf(target, startingRef);
    LOGGER.debug("| Set field {}: {}", key, value);
    BsonDocument result = blankUpdateDoc();
    result.compute("$set", (_, existing) -> {
        if (existing == null) {
            return new BsonDocument(key, value);
        } else {
            return existing.asDocument().append(key, value);
        }
    });
    return result;
}

protected <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
    String key = dottedFieldNameOf(target, startingRef);
    LOGGER.debug("| Unset field {}", key);
    return blankUpdateDoc().append("$unset", new BsonDocument(key, new BsonNull()));
}
```

Add imports to `AbstractFormatDriver`:
- `works.bosk.Reference`
- `org.bson.BsonValue`
- `org.bson.BsonNull`
- `static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf`

In `SequoiaFormatDriver`, delete both `replacementDoc` and `deletionDoc` methods. Update call sites to serialize first and pass `rootRef`:
- `submitReplacement`: `replacementDoc(target, newValue)` → `replacementDoc(target, formatter.object2bsonValue(newValue, target.targetType()), rootRef)`
- `submitConditionalCreation`: same change
- `deletionDoc(target)` → `deletionDoc(target, rootRef)`

In `PandoFormatDriver`, delete both `replacementDoc` and `deletionDoc` methods. Call sites already pass a `BsonValue` and a `startingRef`, so no call-site changes needed.

---

### Step 3: Fix `shouldSkip`/`shouldNotSkip` inconsistency and lift to base class

Sequoia has `shouldNotSkip(revision)` (positive sense) and Pando has `shouldSkip(revision)` (negative sense). Their logic also differs: Sequoia uses `>` while Pando uses `<=`. Pando's version is correct: skip if the revision is at or below the skip revision.

In `AbstractFormatDriver`, add:
```java
protected volatile BsonInt64 revisionToSkip = null;

protected boolean shouldSkip(BsonInt64 revision) {
    return revision != null && revisionToSkip != null
        && revision.longValue() <= revisionToSkip.longValue();
}
```

In `SequoiaFormatDriver`:
- Delete `private volatile BsonInt64 revisionToSkip`
- Delete `shouldNotSkip` method
- In `onEvent`, replace `if (shouldNotSkip(revision))` with `if (!shouldSkip(revision))`

In `PandoFormatDriver`:
- Delete `private volatile BsonInt64 revisionToSkip`
- Delete `shouldSkip` method
- All existing call sites use `shouldSkip(revision)` — no changes needed

---

### Step 4: Move `collection`, `downstream`, and `flushLock` to the base class

In `AbstractFormatDriver`, add fields:
```java
protected final TransactionalCollection collection;
protected final BoskDriver downstream;
protected final FlushLock flushLock;
```

Since the class uses `@RequiredArgsConstructor`, these fields will automatically be included in the generated constructor. Update the `super(...)` call in each subclass constructor to pass the three additional values. Delete the corresponding field declarations from both subclasses.

---

### Step 5: Lift `onRevisionToSkip`

Depends on Steps 3 and 4.

In `AbstractFormatDriver`, add:
```java
@Override
public void onRevisionToSkip(BsonInt64 revision) {
    LOGGER.debug("+ onRevisionToSkip({})", revision.longValue());
    revisionToSkip = revision;
    flushLock.finishedRevision(revision);
}
```

In both subclasses, delete `onRevisionToSkip`.

---

### Step 6: Lift `flush` and `close`, with `readRevisionNumber` as abstract

In `AbstractFormatDriver`, add:
```java
protected abstract BsonInt64 readRevisionNumber() throws FlushFailureException;

@Override
public void flush() throws IOException, InterruptedException {
    flushLock.awaitRevision(readRevisionNumber());
    LOGGER.debug("| Flush downstream");
    downstream.flush();
}

@Override
public void close() {
    LOGGER.debug("+ close()");
    flushLock.close();
}
```

Add imports to `AbstractFormatDriver`:
- `java.io.IOException`
- `works.bosk.exceptions.FlushFailureException`

In both subclasses:
- Delete `flush()`
- Delete `close()`
- Change `readRevisionNumber()` from `private` to `protected` if it isn't already

---

### Step 7: Lift `writeManifest`

Depends on Step 4 (`collection` in base class).

In `AbstractFormatDriver`, add:
```java
protected void writeManifest(Manifest manifest) {
    BsonDocument doc = new BsonDocument("_id", MainDriver.MANIFEST_ID);
    doc.putAll((BsonDocument) formatter.object2bsonValue(manifest, Manifest.class));
    BsonDocument filter = new BsonDocument("_id", MainDriver.MANIFEST_ID);
    LOGGER.debug("| Initial manifest: {}", doc);
    ReplaceOptions options = new ReplaceOptions().upsert(true);
    UpdateResult result = collection.replaceOne(filter, doc, options);
    LOGGER.debug("| Manifest result: {}", result);
}
```

Add imports to `AbstractFormatDriver`:
- `com.mongodb.client.model.ReplaceOptions`
- `com.mongodb.client.result.UpdateResult`

In `SequoiaFormatDriver`:
- Delete `writeManifest()`
- In `initializeCollection`, call `writeManifest(Manifest.forSequoia())`

In `PandoFormatDriver`:
- Delete `writeManifest()`
- In `initializeCollection`, call `writeManifest(Manifest.forPando(format))`

---

### Step 8: Lift `description` and `toString`

In `AbstractFormatDriver`, add:
```java
private final String description;

@Override
public String toString() {
    return description;
}
```

Since the class uses `@RequiredArgsConstructor`, `description` will be included in the generated constructor. Add it as the last field so it's the last constructor parameter. Update the `super(...)` call in each subclass to pass `getClass().getSimpleName() + ": " + driverSettings` as the final argument.

In both subclasses, delete `private final String description` and `toString()`.

---

### Recommended Execution Order

1. Step 1 — no dependencies
2. Step 2 — no dependencies
3. Step 3 — no dependencies
4. Step 4 — no dependencies; unblocks 5, 6, 7
5. Step 5 — depends on 3 and 4
6. Step 6 — depends on 4
7. Step 7 — depends on 4
8. Step 8 — depends on 4 (since it adds a field to the Lombok constructor)

Each step should compile and pass tests independently before moving to the next.