# Boson parser performance: investigation notes and deferred ideas

This document captures the notes from a performance investigation of the
boson JSON parser in August 2026. The investigation produced the commits in
this branch and the measurements reported below; the ideas at the end are
ones we discussed but never acted on, recorded with enough detail to pick
them up later if the need ever arises.

## Context

The subject is the boson *compiled parser* (the `SpecCompiler`-generated
parsers) versus two references:

- `manual` (`boson/src/test/java/works/bosk/boson/ManualTest.java`) — a
  hand-written parser for `OneOfEach`, the "ceiling" reference.
- Jackson — the mainstream baseline.

The goal was to close the gap to `manual`, using profiling and targeted
changes. A secondary theme emerged: improvements that lift **both** parsers
are wins even when they don't close the gap.

### Machine and JDK caveats

- Apple M3 Pro, macOS, JDK 26-tem (test classes are class-file v70, so
  benchmark JVMs must run on 26).
- The machine is **noisy for microbenchmarks**: the agent harness competes
  for CPU, and there is thermal drift between runs. `manual`/Jackson act as
  controls; never trust a single run; use interleaved before/after A/B.

### Running the benchmark

```sh
# From the repo root:
./gradlew --no-configuration-cache --init-script benchmark.init.gradle --console=plain \
  :boson:testClasses :boson:printTestRuntimeClasspath
# Generate the big files (once):
javac -cp "$(cat boson/build/test-runtime-classpath.txt)" -d /tmp/bigfilegen BigfileGen.java
java -cp "/tmp/bigfilegen:$(cat boson/build/test-runtime-classpath.txt)" BigfileGen 100000

# From boson/:
java -cp "$(cat build/test-runtime-classpath.txt)" org.openjdk.jmh.Main \
  '^works\.bosk\.boson\.ParseBenchmark\.(compiled_default|manual|jackson|compiled_list|jackson_list)$' \
  -f 2 -wi 3 -i 8 -r 1 -w 1 -t 1
```

Workloads: a single `OneOfEach` record (`CharArrayJsonReader`) and 100k
`OneOfEach` records from disk (`ByteChunkJsonReader`).

### Profiling methodology lessons

- **JFR on the fork**: pass `-jvmArgs "-XX:StartFlightRecording=filename=...,settings=profile,dumponexit=true"` to JMH, or the recording lands on the parent and captures nothing useful.
- **Isolate the measurement phase**: add `delay=6s` (past warmup) to the recording; the whole-fork recording is dominated by warmup-phase compilations and misleads.
- **Normalize by per-document time**: convert profile sample counts to `count / rate / docs-parsed`. Comparing raw sample counts between two parsers with different throughputs is meaningless; per-doc ns made the gap visible.
- **`-XX:MaxInlineSize=400` experiment**: raises inlining at *cold* call sites (default 35). Used to test whether C2's refusal to inline a hot method is the bottleneck. (It was, for `nextStringChar`; later it showed the member-name helpers were already inlined in steady state.)
- **`PrintInlining` with full warmup** (`-wi 4`) distinguishes cold-site (`MaxInlineSize`=35) from hot-site (`FreqInlineSize`=325) inlining.
- **Assertions count toward C2's inline-size estimates even when disabled** (`-da`): `javac` emits assert bytecode unconditionally, and C2 sizes the method from bytecode. Two responses unblock inlining of a hot method: extract the assert into a helper, or (the choice landed on in this branch) comment it out — the assertions are documentation anyway, and commenting removes the bytecode entirely with no production cost.

## Where things stand (committed work)

Landed as pull request #439. Each commit is a single logical change, in
refactoring-then-functionality order, and each passes `:boson:test`:

| commit | what |
|---|---|
| ISO-8859-1 | Decode number text with ISO-8859-1 instead of UTF-8 (the number `CharSequence` is ASCII-only, so this is a plain byte-to-char copy). |
| consumeEndOfString | Consume the closing quote as a distinct string operation, so whole-name-consumed member names skip the closing-quote scan. Carries the chunked-reader regression test. |
| numbers from `CharSequence` | Parse numbers from the reader's `CharSequence` without an intermediate `String`. |
| token peeks | Eliminate redundant token peeks: emit `consumeTokenWithOrdinal` for already-peeked tokens instead of re-peeking. |
| assert-commenting | Comment out the hot-path assertions (they counted toward C2's inline-size estimate even with `-da`). |
| rare-path outlining | Outline the rare/error/escape/fallback paths of `readNumberAsCharSequence`, `parseBoolean`, `nextStringChar`, `consumeString` into `_rare`-suffixed methods. |
| stop inlining | Stop inlining scalar `TypeRefNode`s (`InlineScalarRefs` retained but not invoked). |
| member-name skip | Skip member names in bulk in `CharArrayJsonReader` (`skipStringChars`/`skipToEndOfString` fast paths). |

### What was dropped, and why

The branch was assembled from a larger body of work, discarding anything that
didn't earn its place:

- **The SWAR word-scanning work** (`Swar.java` helpers, 7-byte carry-over,
  word-scanning fast-read/skip, short-string scalar path, and the bulk string
  skip in `ByteChunkJsonReader`): measured **flat** end-to-end on the list
  workload (base 9.32 → 9.27 ops/s, control flat). The benchmark's short
  member names rarely trigger word-scans, so the added complexity wasn't
  justified. The ISO-8859-1 change was kept (independent, principled).
- **`ConstructorNode`** (direct canonical-constructor calls for public
  records, plus `Simplifier`/`SubtreeTransformation`): dropped — it is the
  C1-warmup special case of the deferred `CallHandle` work (#1 below), and
  the strategic direction is a handle specialization, not a node.
- **The Jackson-in-CI benchmark job**: dropped (JMH in CI is noisy; the
  harness can be reconstructed from git history if wanted).
- **The invokedynamic `ConstantCallSite` experiment**: dropped (net-zero
  revert within the branch).
- **The small-list benchmark** (`IntPair`), folded into the dropped
  `ConstructorNode` commit, went with it.

Naming conventions adopted: peeled rare/error/escape/fallback paths get a
**`_rare` suffix**; hot-path assertions are **commented out** (not deleted,
not extracted) and carry their original form as documentation.

### Measured results

Final branch vs base (interleaved A/B, controls flat within noise):

- Small-document workload: compiled_default **+36.6%**, manual **+54.8%**.
- List workload (100k records, disk): compiled_list **+9.5%**.

For reference, the pre-round state measured compiled_default 1,479,992 ops/s
vs manual 1,673,863 vs Jackson 1,213,489; the final compiled_default is
about 2.0M ops/s.

## Deferred ideas

### 1. The `CallHandle` hierarchy: `DirectHandle` / `ConstructorHandle` / `CarriedHandle` (the big one)

**The idea.** Replace the raw `TypedHandle` fields with a sealed hierarchy so
the generated code dispatches direct method/constructor calls with real
invoke opcodes instead of the curried `MethodHandle.invokeExact` path (which
goes through LambdaForm machinery).

```java
sealed interface CallHandle permits DirectHandle, ConstructorHandle, CarriedHandle { }
```

- **`DirectHandle`** — expressible + accessible direct method call
  (`REF_invokeStatic`/`Virtual`/`Interface`). Codegen: one invoke opcode.
- **`ConstructorHandle`** — expressible + accessible constructor
  (`REF_newInvokeSpecial`). Codegen: `new owner; dup; <params>; invokespecial <init>`.
- **`CarriedHandle`** — everything else (bound/adapted/custom): the current
  `TypedHandle` behavior (curried static field + `invokeExact`).

`CallHandle.of(TypedHandle)` classifies at build time: `reflectAs` succeeds
**and** the target is accessible from the generated code (public class +
public member + package exported via `Module.isExported`) → Direct/Constructor
by reference kind; else Carried. Direct/Constructor also carry the
`MethodHandle` so the interpreter can still `.invoke()`.

**Motivation.** The map accumulation (`LinkedHashMap.put` per entry) runs
through `MethodHandle.invokeExact` and costs **+27 ns/doc** in the compiled
parser — the single largest mapped item in the gap decomposition (see #4).
`TimeUnit.valueOf` and `RepresentAs.from` are direct static handles that go
through the same carried path. An earlier `ConstructorNode` experiment (a
node-level special case of what a `ConstructorHandle` does) was dropped; a
handle-level version is the generalization.

**Why the node approach failed.** A parse-only `DirectCallNode` can't replace
the *value-position* conversions (`RepresentAsSpec`, `EnumByNameNode`) because
those are **bidirectional** — the generator needs `toRepresentation` and the
enum name emission (see `SpecInterpretingGenerator`). The parse-only handle
positions are the **accumulators** (and emitters), where the fields are
`TypedHandle`s — so the right abstraction is a handle specialization, not a
node. (This is the "we should be designing DirectHandle and ConstructorHandle,
not DirectNode and ConstructorNode" conclusion.)

**Phasing** (each commit green):

1. **Hierarchy + compiler dispatch, no field changes.** Add the three kinds +
   the classifier; add a `_invokeHandle(CallHandle, name)` in
   `SpecCompiler`'s `ParserCodeBuilder` replacing the
   `curryAndLoad`+`_invokeExact` call sites, switching on the kind. Existing
   handles are mostly bound → Carried → behavior unchanged; the unadapted
   record finisher classifies as ConstructorHandle (reproducing the `new`+
   `invokespecial` the dropped `ConstructorNode` used to emit).
2. **Defaults → direct handles (the perf validation).** Rebuild the
   `TypeScanner` defaults with raw direct handles: `ArrayList.add`,
   `LinkedHashMap.put`, `Collections.unmodifiableList` via
   `findVirtual`/`findStatic`; the record finisher as an *unadapted*
   constructor handle; `valueOfHandle`/`parseHandle` are already direct.
   Measure whether the +27 ns/doc map item shrinks before doing the big
   refactor.
3. **Manifest in the fields.** Change the ~20 handle-holding field sites from
   `TypedHandle` to `CallHandle` (FixedObjectNode.finisher, both accumulators,
   both emitters, RepresentAsSpec, ComputedSpec, ParseCallbackSpec,
   MemberPresenceCondition, RecognizedMember); factories classify at build
   time; interpreter/generator access `.invoke()`/`.handle()` through the
   union. **Remove `ConstructorNode` and the `Simplifier` rewrite** — the
   record finisher's ConstructorHandle drives construction uniformly, and a
   bare `new ArrayList` creator becomes a ConstructorHandle too.
4. **Emitters** (`ArrayEmitter`/`ObjectEmitter`): mostly Carried (iterators
   are bound).

**Risks.**

- **The record finisher's `asType` adaptation** (`TypeScanner.recordFinisher`)
  can turn the constructor handle non-direct (boxed/erased member types) →
  Carried → `invokeExact` → the C1 constructor-handle cliff returns for those
  records. Phase 2 must guarantee the finisher stays a ConstructorHandle by
  moving any member-type adaptation into the emitted code. This is the
  non-negotiable requirement (the dropped `ConstructorNode` guaranteed it
  by construction).
- The accessibility predicate must be right; the `SpecCompiler.verify()`
  bytecode step (on under assertions) is the safety net.
- Custom wrangler accumulators stay Carried — the `from(wrangler)` factories
  are unchanged.

**Key files:** `mapping/spec/handles/TypedHandle.java`,
`.../ArrayAccumulator.java`, `.../ObjectAccumulator.java`,
`.../TypedHandles.java`; `mapping/TypeScanner.java`
(`computeArrayListAccumulator`, `computeLinkedHashMapAccumulator`,
`recordFinisher`); `codec/compiler/SpecCompiler.java` (`_invokeExact`,
`curryAndLoad`); `codec/interpreter/SpecInterpretingParser.java`.

### 2. `DirectCallNode` as a spec node (superseded by #1)

A `JsonValueSpec` representing a direct method call, "in the same spirit as
ConstructorNode." Superseded by the `CallHandle` design for two reasons:
bidirectionality (it can't replace `RepresentAsSpec`/`EnumByNameNode` without
breaking the generator) and category confusion (ConstructorNode is an
`ObjectSpec` *node*; the accumulator creator is a `TypedHandle` *field* — a
node can't occupy a handle slot without restructuring the accumulator). Do
not revive; #1 is the resolution.

### 3. Folding `ConstructorNode` into a general mechanism (folded into #1)

The dropped `ConstructorNode` was a specialized `ObjectSpec` (sibling of
`FixedObjectNode`) that emitted `new`+`invokespecial` for **public records**.
A `ConstructorHandle` generalizes it to any accessible class + accessible
constructor (resolving the `new ArrayList` creator case) and subsumes it.
Sequencing caveat from the discussion: until Phase 2 proves the record
finisher stays a ConstructorHandle, records fall back to the curried
`invokeExact` path — the C1-warmup cost the `ConstructorNode` avoided.

### 4. The remaining compiled-vs-manual gap

Per-document decomposition (measurement-phase JFR, normalized): the gap is
~119 ns/doc, split roughly as:

| cost | compiled ns/doc | manual ns/doc | Δ |
|---|---|---|---|
| member-name skip | 88 | 98+19 | −29 |
| peeks (`skipInsignificant`) | 69 | 36 | +33 |
| own generated code | 95 | 59 | +36 |
| map (`HashMap`) | 43 | 16 | +27 |
| `BigDecimal` | 40 | 24 | +16 |
| `startingWith` | 18 | 39 | −21 |
| doubles | 39 | 27 | +12 |

Notes for when this is revisited:

- The **map** item (+27) is the `CallHandle` target (#1).
- The **peeks** (+33) are *not* a `manual` shortcut — verified that `manual`
  peeks every value (`readAnyValue`, `readInteger`, etc.). Partly the dispatch
  around each peek in the non-inlined `parse_NullOr_Ref_*` chain.
- The **generated-code** item (+36) is the per-value dispatch the outlining
  introduced; `GeneratedCodec` frames vs `ManualTest` frames.
- The member-name trie is **not** the gap: `-XX:MaxInlineSize=400` gave no
  benefit, `PrintInlining` shows the trie helpers inline at hot sites, and
  compiled's member-name skipping is cheaper than manual's.

### 5. Custom fast number parser (`Util.parseInt`/`parseLong` fast path)

**Idea.** Give `Util.parseInt`/`parseLong` a general fast path (optional sign,
full range, overflow-checked) with a `_rare` fallback to the JDK parser for
malformed input, instead of `Integer.parseInt(CharSequence, 0, len, 10)`.

**Motivation.** `Integer.parseInt` (314B) was ~27% of the int-heavy list
profile and inlines inconsistently (it sits right at `FreqInlineSize`=325).

**Why deferred.** It's **not** a compiled-vs-manual difference — both call the
identical JDK parser (`Long.parseLong(s, 0, s.length(), 10)`). A fast path for
"small non-negative ints" would have been over-fit to the benchmark's
arbitrary `IntPair` values; only a *general* parser (all valid ints, malformed
as the sole rare path) is defensible, and its win over a sometimes-inlined
`Integer.parseInt` is unproven. If revived: prototype the general parser,
measure, keep only if it wins.

### 6. `ByteChunkJsonReader` outlining (`consumeNumber`, `consumeSyntax`, `consumeEndOfString`)

These methods (107B / 55B / 77B) don't inline. Shelved: the list workloads
(which use this reader) have **no `manual` comparison**, so this is
absolute-throughput work, not gap work. Apply the same hot/cold outlining
pattern as `CharArrayJsonReader` if list-path throughput matters.

### 7. Shared number parsing (`BigDecimal`, doubles, `Long.parseLong`)

`BigDecimal.<init>` (1023B), `FloatingDecimal` (double parsing), and
`Long.parseLong` (324B) appear at similar counts in **both** parsers (~12%+ of
each profile) — they're shared costs, not the gap. Improving them is a real
absolute-throughput project (custom BigDecimal/double parsing) but large; not
started.

### 8. `InlineScalarRefs` policy

The pass (`mapping/opt/InlineScalarRefs.java`, + its test) is **retained but
no longer invoked** in `Optimizer.java` (the stop-inlining commit removed the
invocation, and the postorder machinery went with it). Rationale:
inlining scalar `TypeRefNode`s into the monolithic generated object method
doesn't help — C2 refuses to inline *anything* into it (`DesiredMethodLimit`),
so an inlined scalar became a non-inlined runtime-helper call. Outlining each
member value into its own small generated method let C2 inline the runtime
helpers into those. Revisit the policy once the generated methods are small
enough that scalar inlining would pay (e.g., after method-splitting or the
`CallHandle` work).

## Pick-up checklist

- The committed work is in pull request #439.
- The highest-value deferred item is **#1 (`CallHandle`)** — Phase 2
  (defaults → direct handles) is the perf validation and can be done before
  the field-type refactor.
- Re-read the profiling-methodology notes above before measuring anything;
  the machine is noisy and the per-doc normalization is essential.
