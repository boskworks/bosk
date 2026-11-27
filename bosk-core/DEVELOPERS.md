## Bosk-core developer's guide

This guide is for those interested in contributing to the development of the `bosk-core` module.
(The guide for developers _using_ the module is [USERS.md](../docs/USERS.md).)

It records the design decisions behind the module's concurrency, and the evidence for them. Most of
the code makes these obvious; the parts that don't are what follow.

### Hook triggering and execution

#### The drainer must not take the driver's monitor

This is the rule the rest of the design serves, and the code can't state it, because it is enforced by
not writing something. The driver's monitor is held across whole updates, so it is nearly saturated
whenever updates are flowing. A thread that needs it occasionally, as the drainer does, isn't merely
slowed by that: with intrinsic monitors it is starved, and the hook rate falls by orders of
magnitude rather than by a factor. [#574](https://github.com/boskworks/bosk/issues/574) has the measurements.

Swapping in a `ReentrantLock` doesn't settle it either. With one root hook, eight updating threads,
and an unfair `ReentrantLock` in place of the driver's monitor, the hook rate recovers from 44 calls
per second to about 15,000, which is still an order of magnitude below the 130,000 to 150,000 that
the drainer reaches when it doesn't need that lock at all. The kind of lock decides whether the
drainer is starved or merely delayed; either way, three acquisitions per batch put a ceiling on
how often hooks can run that has nothing to do with what the hooks are doing.

So every read the drainer makes is either immutable, volatile, or guarded by a lock whose section is
tens of nanoseconds. The tempting alternative is always the driver's monitor, because the value you
want is usually in there.

#### The locks

| # | Mechanism | Order | What it keeps true | Acquired by |
|---|---|---|---|---|
| 1 | The driver's monitor | 1 | An update's state change and the hook work it accepts are recorded together, in update order | Producers, for a whole update |
| 2 | `hookWorkMonitor` | 2 | A notification recorded before `reportCaughtUp` reads the queue and backlog is seen by that read | Both: producers to record a notification; the drainer to poll the queue, to move work out of the backlog or back into it, to record a completed notification, and to decide whether any remains |
| 3 | The controller's monitor | 3 | A transition between modes is decided from counts that describe the queue as it is | Both, briefly |
| 4 | `drainPermit` | — | Hook work is run by one thread at a time | The current drainer, for as long as it drains |

```
producers:  driver ──→ hookWorkMonitor ──→ controller
drainer:    drainPermit ──→ hookWorkMonitor ──→ controller
```

`hookWorkMonitor` and the controller's monitor are held briefly: a queue add or poll, a few counters,
or one map write per call that an update produced. That is why they can be ordinary
`synchronized` blocks even after what we just measured. The expensive part of compaction is the
comparison of the two state snapshots, and that happens before the lock is taken. Anything whose cost
grows with the length of the queue has to stay outside these sections, or the waiting comes back.
The exception is the flush that copies the queue into a batch, whose length the threshold bounds.

#### Invariants with no single home

**Registration indices are stable.** A registration's index identifies it for the lifetime of the
runner and is never reused. Compacted calls carry indices rather than registrations, so
unregistering a hook, or reordering the list, would run calls against the wrong registration
rather than fail.

**Registration is ordered with updates.** `register` is called with the driver's monitor held, and a
notification carries the range of registrations that existed when it was accepted, so a hook
registered later does not receive an earlier update.

**A batch's snapshot is taken with the backlog it describes.** Reading the state before detaching the
backlog lets a call recorded in between run against a state older than itself, which for a
creation looks like a creation and deletion, and the call is dropped.

**Which direction a race can go matters.** Recording into the backlog after the drainer has reported
caught up strands that work, because only a compacting mode runs the backlog. Queueing a
notification just as another producer enters overload mode is harmless, because a batch flushes the
queue ahead of the backlog.

#### Cost, and where the headroom is

A hook execution costs single-digit microseconds in the wrapper before the body runs: a fresh virtual
thread, MDC setup, and a read session. A hook that does nothing still costs that, which puts the
ceiling on hook calls at about 140,000 per second, and the rate now sits at that ceiling at every
thread count. Raising it means making the wrapper cheaper, for example one virtual thread per batch
rather than one per call.

Hook rate under contention is unstable from run to run. Compare medians and shapes across a range of
thread counts, on one machine, and treat a single figure as telling you nothing.

#### Glossary

- **Hook work** is the umbrella term for what an update has triggered and the drainer has not
  run yet. It waits in one of two structures, depending on the mode.
- A **notification** (`HookNotification`) is one update's announcement, carrying the state it was
  computed against and the registrations that had been made when it happened. Notifications wait
  in the **queue** (`hookQueue`).
- Expanding a notification produces **calls**: one per hook registration and changed reference,
  with whether the reference existed beforehand. A call is what runs.
- **Compacting** is what overload mode does to a notification instead of queueing it: it expands it
  immediately and merges the calls into the backlog. The **backlog** (`HookBacklog`) collects
  calls, one entry per hook registration and reference, until a drain runs them. `CompactedBatch`
  is a backlog detached for one drain, together with the state it runs against.
- A producer **accepts** a notification (`accept`), which either puts it on the queue and counts it
  toward the threshold (`recordQueuedNotification`) or compacts it into the backlog (`acceptCompacted`).
- The drainer **polls** the queue, **expands** each notification into calls, **detaches** the
  backlog into a batch, **clears** both on interrupt, and **reports caught up** when nothing
  remains.
- Avoid _publish_ and _publication_ here: in this project they mean
  `MainDriver.publishFormatDriver` in `bosk-mongo`, or safe publication of `this` out of a
  constructor in `Bosk`. Avoid _deliver_ and _delivery_ for hook work too: a hook is triggered and
  then run.
