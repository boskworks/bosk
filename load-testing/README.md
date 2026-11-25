# load-testing

A load-testing harness for Bosk drivers. This module is not published; it is a development tool.

The harness runs a conflict-free workload against a driver. Each worker owns a disjoint slice of the root catalog and repeatedly replaces the `string` field of its entities, so the final state is deterministic and can be verified exactly. It measures update and flush latency with per-worker HdrHistograms, samples heap and GC once per second, and writes the results to CSV.

Supported drivers:

- `simple`: the in-memory driver.
- `mongo-sequoia`: MongoDriver using SEQUOIA.
- `mongo-pando`: MongoDriver using Pando with the whole state in one document.
- `mongo-pando-grafted`: MongoDriver using Pando with `/catalog` and `/sideTable` grafted into separate documents.

The Mongo drivers start a disposable MongoDB replica set with Testcontainers, so Docker must be running. Each run gets a fresh container and a fresh collection.

## Running

Run one configuration per process so that JIT state and heap do not carry over between levels of a ramp.

```bash
./gradlew :load-testing:run --args="--threads=1 --warmup=10s --duration=30s --label=one"
./gradlew :load-testing:run --args="--threads=P/2 --warmup=10s --duration=30s --label=half"
./gradlew :load-testing:run --args="--threads=P --warmup=10s --duration=30s --label=all"
./gradlew :load-testing:run --args="--threads=2P --warmup=10s --duration=30s --label=twice"
./gradlew :load-testing:run --args="--threads=4P --warmup=10s --duration=30s --label=four"
./gradlew :load-testing:run --args="--driver=mongo-sequoia --threads=6 --warmup=3s --duration=10s --label=seq"
```

`P` is `Runtime.availableProcessors()`. Thread counts may also be absolute integers, `P/2`, `2P`, or `2*P`.

Pass JVM arguments to a run with `-PloadJvmArgs`, for example `-PloadJvmArgs=-Xmx256m`. This applies only to the forked run JVM.

Options:

| Option | Default | Meaning |
|---|---|---|
| `--driver` | `simple` | `simple`, `mongo-sequoia`, `mongo-pando`, or `mongo-pando-grafted` |
| `--entities` | `10000` | Number of entities in the root catalog |
| `--threads` | `P` | Worker threads |
| `--warmup` | `10s` | Warmup duration; supports `ms`, `s`, `m`, `h` |
| `--duration` | `30s` | Measurement duration |
| `--flush` | `everyOp` | `everyOp`, `everyN`, or `end` |
| `--flushEvery` | `100` | Flush interval for `everyN` |
| `--valueBytes` | `16` | Length of the string values written by the workload |
| `--hookLatency` | `0ms` | Sleep time for each hook; zero means it doesn't sleep |
| `--hookScope` | `root` | Hook scope: `root` or `entity` |
| `--hookCount` | `0` | Number of hooks to register; zero disables hooks |
| `--hookQueueMaxSize` | `1000` | Hook queue threshold at which overload mode begins; zero means always compacted |
| `--readers` | `0` | Reader threads running alongside the writers; zero disables reads |
| `--readShape` | `leaf` | What readers read: `leaf`, `entity`, or `catalog` |
| `--sessionLifetime` | `perOp` | `perOp` opens a session per read; `perThread` holds one open for the run |
| `--stallTimeout` | `60s` | How long to wait for workers to stop before dumping threads |
| `--label` | empty | Label included in the output |
| `--out` | `build/load-results` | Output directory |

The output directory contains:

- `summary.csv`: one row per run, with configuration, throughput, latency percentiles, heap, GC, and environment.
- `samples.csv`: one row per second per run, with elapsed time, operation count, heap, and GC.

Both files are appended to, so a ramp produces one analysis-ready file.

The summary has `success`, which is true only when the run completed, saw no errors, and verified
its final state. `verified` on its own means the final state matched the values the workload last
managed to submit; a run that dropped updates can still be `verified`. `verification_checked` and
`verification_failed` show how many targets were examined and how many were wrong. Verification
now compares the workload's own targets rather than whole-root equality, so it does not allocate a
second copy of a large catalog.

## Measurement notes

- Latency is recorded with HdrHistogram at 3 significant digits, so percentiles have about 0.1% relative error.
- Submit and flush latency are recorded separately.
- The workload is closed-loop. When the system slows, workers wait, so offered load falls. Closed-loop numbers are useful for finding sustainable concurrency; an open-loop mode is still to come.
- Warmup is excluded from the metrics.
- `heap_used_before` and `heap_used_after` in the summary are point-in-time values. The time series in `samples.csv` is the better source for heap and GC behaviour.
- The `max` latency is often a GC or scheduling spike. Look at the percentiles for the shape of the distribution.
- `hook_invocations` counts the hook calls that actually ran during measurement. When the hook queue reaches `hookQueueMaxSize`, updates are compacted instead, and the count plateaus below the update count even as throughput rises.
- `hook_body_*` times only the hook callback. The delivery wrapper around it, which includes a fresh virtual thread, MDC setup and a read session, is not included, and at one thread it costs about 7 µs per call.

## Performance sanity suite

The suite runs a fixed set of short scenarios, one JVM per scenario, to notice when something has got markedly slower. It is not a benchmark and it does not compare against stored numbers: it writes the measurements and the environment, and you compare two runs by eye, on the same machine.

```bash
./gradlew :load-testing:sanity
./gradlew :load-testing:sanity --args="--label=my-branch --reps=3"
./gradlew :load-testing:sanity --args="--only=update-pando-8,flush-per-update"
./gradlew :load-testing:sanity --args="--list"
```

Each scenario runs ten seconds of warmup and ten seconds of measurement, twice by default. That is about four minutes in total, including one MongoDB container per scenario.

| Scenario | Group | What it measures |
|---|---|---|
| `update-pando-1` | mongo | One thread of disjoint field updates through grafted Pando. |
| `update-pando-8` | mongo | Eight threads, same driver: contention and pool behaviour. |
| `update-sequoia-8` | mongo | Eight threads through Sequoia. |
| `flush-per-update` | mongo | One thread flushing after every update: the flush path a request takes. |
| `submit-simple-8` | core | Eight threads through the in-memory driver: the core submit path with no I/O. |
| `submit-simple-1` | core | The same path with one thread, for comparing against `hooks-root-1`. |
| `hooks-root-1` | core | One thread with a single root hook that does nothing: per-update dispatch. |
| `hooks-root-8` | core | The same hook under eight threads. |
| `hooks-root-2` | core | Two threads: whether hook execution keeps up with the updates. |
| `hooks-root-4` | core | Four threads, between the two previous cases. |
| `hooks-entity-8` | core | Eight threads with one hook scoped to every entity's string field. |
| `read-simple-8` | core | Eight reader threads taking a read session per operation. |
| `read-simple-perthread` | core | The same readers, each holding one read session for the run. |
| `flush-idle` | mongo | Fifty flushes with nothing written in between. |
| `flush-after-burst` | mongo | One flush after each burst of a hundred updates. |
| `init-1000` | mongo | Constructing a bosk whose collection holds a thousand entries. |

| Option | Default | Meaning |
|---|---|---|
| `--label` | `local` | Name used for the output files |
| `--reps` | `2` | Repetitions per scenario |
| `--out` | `build/sanity-results` | Output directory |
| `--only` | all | Comma-separated scenario names |
| `--list` | | List the scenarios and exit |

The output directory contains:

- `sanity-<label>.csv`: one row per scenario and metric, with each repetition, the median, and the range.
- `sanity-<label>.env`: the git revision, JVM, heap, CPU, and MongoDB image, so you can tell whether two runs are comparable.
- `<scenario>-rep<n>.log`: the child process output, for anything that went wrong.

Read the range, not just the median: a scenario whose repetitions disagree by more than about ten percent cannot show a smaller regression. The first repetition of the first scenario sometimes pays for page cache and container warm-up, so `--reps=3` and a second run settle it.

## Current main (2026-11-21)

The harness is rebased onto `upstream/main` at `128ce7ec9`. This is the first run against the
per-document revision design for Pando (`643d6eecc`, `46526c87e`), which removes the
collection-wide root revision write and performs a single-document Pando update as one atomic
write instead of a multi-document transaction.

Control matrix: 10,000 entities, 16-byte values, 3 s warmup, 10 s measurement, flush every update.

| Driver | 1 thread | 6 | 12 | 24 |
|---|---:|---:|---:|---:|
| `simple` | 1,871,981 | 2,410,908 | 2,541,561 | 2,617,810 |
| `mongo-sequoia` | 303 | 661 | 606 | 545 |
| `mongo-pando` | 404 | 690 | 660 | 606 |
| `mongo-pando-grafted` | 66 | 175 | 108 | 99 |
| `mongo-pando-grafted`, flush at end | 2,020 | 7,761 | - | - |

No run produced an error, and every run verified its final state. That is the main result: the
grafted Pando concurrency failures are gone. The old matrix at 10,000 entities reported 0 to 8
ops/s and up to 62 `WriteConflict`/`DisconnectedException` errors from two threads upward.

Two things follow from the numbers:

- **Grafted Pando now scales when flush is not in the way.** With `--flush=end` it reaches 2,020
  ops/s at one thread and 7,761 at six, well above SEQUOIA's 661. The earlier "treat grafted Pando
  as single-writer" guidance is obsolete.
- **`flush()` was O(number of documents).** `PandoFormatDriver.readRevisionsToFlush` lists every
  state document and asks the change receiver about each one. With 10,000 grafted entities,
  flush p50 grows from 13.5 ms at one thread to 229 ms at 24, and throughput falls to 66-99 ops/s.
  At 1,000 entities the same configuration gets 318 ops/s at one thread and 1,266 at six. Flush
  cost, not write concurrency, is the first bottleneck for a grafted Pando deployment with
  flush-per-update. The change-stream barrier below (#567) replaces this scan.

`mongo-pando` (one big document) also improved sharply at one thread, from 28 to 404 ops/s,
consistent with dropping the pre-delete and the transaction. It still plateaus around 600-690
ops/s at higher concurrency, which is the single-document write ceiling. `SEQUOIA` is unchanged in
shape: 303 → 661 → 606 → 545, with submit p99 growing to 720 ms. Since submit performs a
synchronous database write, that tail is document-level write queueing, not the Bosk monitor.

## Change-stream flush barrier (2026-11-21)

Branch `flush-barrier`. This replaces the per-document
revision scan in `PandoFormatDriver.flush()` with a change-stream probe from the receiver's
applied position, and retires the per-document `incarnation` and `revision` fields (#567).
The scan numbers below are from the pre-change commit.

Grafted Pando, 10,000 entities, 16-byte values, per-op flush, 10 s warmup, 30 s measurement.

| Threads | Scan ops/s | Scan flush p50 | Barrier ops/s | Barrier flush p50 |
|---:|---:|---:|---:|---:|
| 1 | 69 | 13.2 ms | **172** | **4.5 ms** |
| 6 | 197 | 29.0 ms | **909** | **5.8 ms** |
| 12 | 112 | 104.9 ms | **1,027** | **10.1 ms** |
| 24 | 100 | 238.0 ms | **942** | **22.4 ms** |

- Throughput improves by 2.5x, 4.6x, 9.2x and 9.4x; flush p50 falls by 2.9x, 5.0x, 10.4x and 10.6x.
- The barrier's idle flush is 4.5 ms, matching the predicted ~5-7 ms: opening the probe change
  stream dominates, not the await itself.
- Submit latency is essentially unchanged (p50 1.0-2.4 ms), so the write path does not pay for it.
- No errors, no stalls, and `Verified: true (10000 checked, 0 failed)` in every run.
- The p50 growth from 4.5 ms to 22.4 ms at 24 threads is the probe cost multiplying across
  concurrent flush calls. Single-flight is not implemented yet; it is the remaining optimisation.

## Reads and read sessions

Reader threads run alongside the writers and record their own latency histogram. They are separate
threads rather than a read fraction inside the writer loop, so read latency is not confounded with
flush latency.

`mongo-sequoia`, 16-byte values, 3 s warmup:

| Entities | Writers | Readers | Session | Duration | Writes (ops/s) | Reads (ops/s) | Read p99 |
|---:|---:|---:|---|---:|---:|---:|---:|
| 1,000 | 4 | 4 | perOp | 10 s | 1,833 | 15,508,240 | 375 ns |
| 10,000 | 2 | 2 | perThread | 15 s | 474 | 13,826,745 | 167 ns |

Reads are roughly three orders of magnitude cheaper than Mongo writes even with a session opened
and closed per read. The long-lived-session run held two sessions open while writers churned
10,000 entities; heap oscillated between about 60 and 210 MB without monotonic growth, so two
pinned versions are not a retention problem. The useful retention experiment is many long-lived
sessions or repeated start/stop, not a single held session.

## First baseline

Apple Silicon, 12 logical cores (6 performance, 6 efficiency), 9 GB max heap, JDK 27. `simpleDriver`, 10,000 entities, 16-byte values, 5 s warmup, 15 s measurement, flush after every update.

| Threads | Throughput (ops/s) | Submit p50 | Submit p99 | Submit max | GC collections |
|---:|---:|---:|---:|---:|---:|
| 1 | 1,970,068 | 375 ns | 1.042 us | 2.3 ms | 80 |
| 6 | 2,248,866 | 709 ns | 1.541 us | 30.4 ms | 89 |
| 12 | 2,369,657 | 708 ns | 1.333 us | 66.3 ms | 100 |
| 24 | 2,243,474 | 708 ns | 1.667 us | 125.8 ms | 87 |
| 48 | 2,406,233 | 708 ns | 1.167 us | 257.2 ms | 195 |

The in-memory driver plateaus at roughly 2.4 million operations per second on this machine. That plateau is the synchronized `LocalDriver`: it applies one update at a time, so extra threads add contention rather than parallel work. The per-update critical section is about 420 ns, which matches the single-thread submit mean of 427 ns. Adding threads beyond the performance-core count does not improve throughput; it raises mean and tail latency instead. The p99 stays around 1.3 to 1.7 microseconds while the maximum grows into the hundreds of milliseconds, which is consistent with scheduling and GC spikes rather than systemic queueing.

This is the baseline the Mongo runs will be compared against.

## State and value size

At 6 threads, keeping everything else the same:

| Entities | Value bytes | Throughput (ops/s) | Submit p50 | Submit p99 | Heap after | GC collections |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 16 | 2,248,866 | 709 ns | 1.541 us | 17.2 MB | 89 |
| 100,000 | 16 | 1,568,365 | 1.083 us | 2.209 us | 1,211.8 MB | 24 |
| 10,000 | 1024 | 1,897,315 | 875 ns | 1.875 us | 535.1 MB | 77 |

A larger catalog lowers throughput and raises per-update latency, even without serialization in the driver. Larger values cost allocation and GC; the 100,000-entity run had fewer collections but each one was longer, for 263 ms of total GC time against 87 ms for the 10,000-entity run. The `heap_used_after` column is a point-in-time value; use `samples.csv` for the sawtooth.

## Slow hooks

A root-scope `BoskHook` that sleeps while multiple workers submit updates. Before overload mode, each queued execution captured the state root for its update, so a slow hook retained state versions until the heap was exhausted.

### Before overload mode

`simpleDriver`, 1,000 entities, 2 s duration, 256 MB heap, 1 ms hook:

| Threads | Hook | Result |
|---:|---|---|
| 4 | none | completed |
| 1 | 1 ms | completed |
| 2 | 1 ms | OutOfMemoryError |
| 4 | 1 ms | OutOfMemoryError |

The one-worker run drained its own hooks and completed. With two or more, the non-draining workers kept submitting while the drainer slept, and the unbounded queue exhausted the heap.

### After overload mode

The same matrix, with the default `hookQueueMaxSize` of 1,000:

| Threads | Hook | Result | Throughput | Heap after |
|---:|---|---|---:|---:|
| 4 | none | completed | 2,418,592 ops/s | 44.5 MB |
| 1 | 1 ms | completed | 703 ops/s | 36.2 MB |
| 2 | 1 ms | completed | 2,585,855 ops/s | 18.9 MB |
| 4 | 1 ms | completed | 2,054,262 ops/s | 15.0 MB |

All runs verified the final state. The overload warning appeared in the hook runs. The 1 ms hook still limits a single submitter that drains its own work, but multiple submitters stay fast because their notifications are coalesced.

Further stress results, all with the `simple` driver and 1,000 entities unless noted:

- 8 and 16 threads, 1 ms hook, 10 s: both completed, at about 2.2–2.3 million ops/s, with heap below 37 MB.
- 4 threads, 10 ms hook, 10 s: completed, at about 2.3 million ops/s, with heap below 19 MB.
- 4 threads, 100 ms hook, 10 s: completed in about 10 s with a 10 s stall timeout, using ~43 MB of heap and one overload warning. Before the two-small-batch exit rule, the same run needed about 85 s to drain a ~750-call per-event tail. The hook still runs at its serialized limit of 10 calls/s; the improvement comes from coalescing the first undersized batch instead of draining that tail per event.
- 4 threads, 1 ms hook, 32 MB heap, 10 s: completed, with about 13 MB live after the run.
- 4 threads, 1 ms hook over every entity in a 10,000-entity catalog, 64 MB heap, 10 s: completed, with about 45 MB live after the run.
- 100 root hooks, 1 ms each, 4 threads, 10 s: completed at about 99,000 ops/s. 1,000 root hooks completed at about 14,000 ops/s. Memory stayed bounded; the cost was serial hook execution.
- A 60 s hook stalled after the harness timeout, with about 59 MB live. It did not exhaust the heap.

The threshold is the memory bound. With a 64 MB heap, 10,000 and 20,000 queued executions completed; 50,000 reported a stall at a 10 s timeout with about 30 MB live, so that was tail drain rather than OOM; 100,000 exhausted the heap. At 128 MB, 100,000 did not OOM in an earlier run but still produced a long stall; large thresholds remain a configuration hazard. At 256 MB, 1,000,000 still exhausted the heap. The default of 1,000 is safe for this workload; configuring a much larger threshold lets the pre-overload queue retain more state.

### Backlog and probation

The current controller replaces the two-small-batch rule. When the pending set drains and queued work remains, it runs a bounded standard-mode probation of up to `min(hookQueueMaxSize, 64)` hook executions initially. The probation succeeds only if at least half its starting backlog drains; otherwise the driver returns to overload. A successful probation runs one final coalesced catch-up generation before standard mode resumes, except when at most one queued execution remains. An early standard-mode overflow doubles the next probe multiplier without a cap, and sustained standard-mode work decays the multiplier linearly.

A probe budget equal to the full queue limit still allowed a slow hook to spend tens of seconds draining a standard queue. Using one queue length as the probe budget ties the probation duration to the user's configured backlog tolerance. The multiplier still grows after a post-probation overflow and decays after sustained standard work.

A 100 ms hook with a 1,000-entry queue limit makes a full probation take about 100 seconds. A 2 s run took 96 s wall-clock to finish because the post-measurement drain was standard-style. That is the user's declared backlog tolerance in action. If it is too slow, the queue limit should be lower.

All runs below completed and verified. Each run emitted one overload warning and did not repeat it.

| Threads | Hook | Scope | Entities | Duration | Throughput (ops/s) | Heap after | Wall-clock |
|---:|---:|---|---:|---:|---:|---:|---:|
| 4 | 1 ms | root | 1,000 | 10 s | 1,950,607 | 49.1 MB | 13 s |
| 4 | 100 ms | root | 1,000 | 2 s | 2,140,666 | 47.1 MB | 96 s |
| 4 | 1 ms | entity | 10,000 | 10 s | 741,831 | 25.7 MB | 30 s |

The saturated runs stay in overload mode for the whole measurement, as expected. The exit path is covered by unit tests, which exercise quiet backlog, a successful trial, a failed trial, and increasing backoff. A load phase that deliberately drops from overload to quiescence would be a useful addition to the harness.

### With Mongo

The Mongo change receiver is the only thread that applies changes locally, and it is also the hook drainer. A slow hook therefore throttles local apply, and the pending state changes accumulate in MongoDB rather than in the in-memory hook queue.

`mongo-sequoia`, 10 entities, 100 KB values, 4 threads, 512 MB heap:

| Hook | Flush | Duration | Submitted | Local apply | Heap | Result |
|---:|---|---:|---:|---:|---:|---|
| 100 ms | everyOp | 30 s | 279 (9/s) | kept up | ~31 MB | healthy |
| 1 s | end | 30 s | 37,882 (1,263/s) | ~revision 70 | ~99 MB | flush timeout, then recovered |

The second run submitted 37,882 updates, but the change receiver had applied only about 70 revisions when the measurement ended. The final flush timed out waiting for revision 37,887 and then recovered. There was no hook-queue OOM.

That is the natural persistent queue: MongoDB holds the pending state changes. It is not a durable hook queue; pending hook notifications are still in memory.

## Mongo results

10,000 entities, 16-byte values, 3 s warmup, 10 s measurement, flush after every update. Each run used a fresh MongoDB 9.0.4 replica set.

### SEQUOIA

| Threads | Ops/s | Submit p50 | Submit p99 | Flush p50 | Errors |
|---:|---:|---:|---:|---:|---:|
| 1 | 317 | 1.421 ms | 2.652 ms | 1.234 ms | 0 |
| 6 | 697 | 2.410 ms | 71.238 ms | 1.450 ms | 0 |
| 12 | 662 | 3.086 ms | 390.857 ms | 1.846 ms | 0 |
| 24 | 605 | 6.992 ms | 622.854 ms | 3.572 ms | 0 |

SEQUOIA sustains roughly 600 to 700 operations per second and stays correct through 24 threads. The flush p50 stays in the low milliseconds, but the submit p99 grows from about 2.7 ms to 623 ms as concurrency rises. That points at queueing in the driver's local apply path rather than at the database round trip.

### Pando one-big-document

| Threads | Ops/s | Submit p50 | Submit p99 | Errors |
|---:|---:|---:|---:|---:|
| 1 | 28 | 26.903 ms | 53.674 ms | 0 |
| 2 | 5 | 63.144 ms | 10.217 s | 0 |
| 4 | 17 | 128.647 ms | 204.210 ms | 84 |
| 6 | 2 | 37.585 ms | 10.251 s | 43 |

One-big-document is limited by rewriting the whole state document. At two threads it already spends 10 s tails in retries; at four and six threads it surfaces errors. A 1,000-entity run at six threads produced no successful updates and one error.

### Grafted Pando

Using `/catalog` and `/sideTable` as graft points:

| Threads | Entities | Ops/s | Errors |
|---:|---:|---:|---:|
| 1 | 10,000 | 912 | 0 |
| 2 | 10,000 | 0 | 0 |
| 4 | 10,000 | 0 | 1 |
| 6 | 10,000 | 8 | 45 |
| 12 | 10,000 | 1 | 62 |
| 6 | 1,000 | 1 | 9 |

Grafting helps a lot at one thread, where it reaches 912 ops/s, faster than SEQUOIA. Each update writes a small entity document instead of the whole state. It does not help under concurrency. Every Pando failure is the same:

```
DisconnectedException: MongoCommandException: error 112 (WriteConflict):
Write conflict during plan execution and yielding is disabled.
Please retry your operation or multi-document transaction.
```

The response carries the `TransientTransactionError` label. `MainDriver` retries a transient transaction error twice immediately, then disconnects and waits; under sustained contention it gives up and surfaces `DisconnectedException`. The 10 s tails correspond to that retry and disconnect cycle.

### Document size at one thread

The same MongoDB instance, one thread, 3 s warmup, 10 s measurement, flush after every update:

| Entities | Pando one-big-document | SEQUOIA | Grafted Pando |
|---:|---:|---:|---:|
| 100 | 1,182 | 1,588 | - |
| 1,000 | 435 | 1,098 | 1,002 |
| 10,000 | 28 | 260 | 819 |

This is the document-size curve, not a MongoDB concurrency ceiling. Pando one-big-document goes from 1,182 ops/s at 100 entities to 28 at 10,000, because each update rewrites the whole state document twice inside a transaction. SEQUOIA also slows down with state size, but it performs one atomic update. Grafted Pando stays around 1,000 ops/s because it writes small entity documents.

At 100 entities, even the Pando transaction path exceeds 1,000 ops/s. The 28 ops/s figure is therefore a property of the one-big-document format at 10,000 entities, not of MongoDB's general write throughput.

### Why Pando behaves differently

Two things differ from SEQUOIA:

1. **Pando uses a multi-document transaction for every update.** `PandoFormatDriver.doReplacement` and `doDelete` call `ensureTransactionStarted`, and `MainDriver` commits the transaction after the operation. `SequoiaFormatDriver.doUpdate` is a single atomic `updateOne` with no transaction.
2. **Pando bumps the `revision` field of the root document on every update.** For a grafted entity, the entity document is separate, but `doReplacement` still calls `doUpdate(blankUpdateDoc(), documentFilter(rootRef))` to increment the collection-wide revision. `multi-doc.md` describes that revision as the collection-wide flush marker. Every update therefore writes the root document.

Concurrent transactions that write the same document conflict. Our workload is deliberately disjoint at the entity level, so in the grafted case the root revision is the only shared write. That makes the conflict easy to attribute. Graft points solve the 16 MB document limit, not the write-concurrency limit.

The pre-delete is a major part of the single-thread cost. `doReplacement` deletes the target field before setting it, so one-big-document writes the large root document twice per update. In a temporary local experiment with the target pre-delete disabled, the picture changed sharply:

| Case | With pre-delete | Without |
|---|---:|---:|
| One-big-document, 10,000 entities | 28 | 313 |
| One-big-document, 1,000 entities | 435 | 1,048 |
| Grafted Pando, 10,000 entities | 819 | 968 |

The concurrency failures remained: four-thread one-big-document still produced errors and 5 ops/s. So the pre-delete explains most of the single-thread gap, while the root revision explains the concurrency failure.

The pre-delete is not optional in general. It exists to prevent MongoDB from suppressing a no-op update event. If a replacement leaves the document unchanged, for example replacing a `Catalog` with an identical one, MongoDB emits no change event, and Pando's gathering logic relies on seeing a main event before the revision event. Deleting the field first forces a change. It is unrelated to `OrphanDocumentMode`: HASTY versus EARNEST controls whether orphan sub-documents are deleted, not whether a no-op update emits an event. Removing the pre-delete globally would be incorrect. Removing it safely requires knowing that the update will still carry the state field in its change event. That is the hard part for `state`: metadata fields like `epoch` are small and trackable after the fact, but comparing or hashing the whole state on every update is not attractive. A per-document marker or revision is the better route, because it makes the event unambiguous without inspecting the state.

Flush is not the main factor. Turning it off moved one-big-document from 28 to 36 ops/s and SEQUOIA from 317 to 456 ops/s.

### Possible directions

- Give the grafted documents their own revisions, or separate the flush/ordering marker, so a grafted update does not write the root document. This is the only way to make Pando scale for concurrent writers.
- Keep the target pre-delete unless the driver can cheaply prove that the state field will appear in the change event. A per-document marker or revision is the practical way to provide that proof without comparing or hashing the state.
- Add such a marker or revision and teach the gathering logic to treat a marker-only event as a no-op. That removes the need for the pre-delete entirely and recovers most of the single-thread gap.
- Reconsider the main-event rule for no-op updates. If the state did not change, a revision-only event could mark the transaction complete and be treated as a no-op. Inferring the state from an event that lacks it is possible in principle, but not attractive for large state.
- The driver already retries a transient transaction error twice immediately, then disconnects and retries once after reconnection. It does not back off indefinitely. A better retry policy could turn a conflict into latency, but it does not remove the root-document bottleneck.
- Batch several updates into one transaction and one revision bump.
- Until then, treat grafted Pando as a single-writer format: 912 ops/s at one thread, with multiple readers.

One reporting caveat: `verified=true` means the final state matched the last successfully submitted values. When updates fail, the workload does not record them, so the final state can still match. The `errors` column and the process exit code are the failure signal.

The next steps are to test the Pando hypotheses in `bosk-mongo`, run SEQUOIA at larger state sizes and under faults, and add a read-only workload.
