# Plan: A self-calibrating PR review agent

## Goal

Build an **AI review agent** that produces PR reviews indistinguishable from those the maintainer of this
repository would write, plus an **on-demand refinement loop** that improves the agent over time using the
methods from the LLM-evaluation literature (Hamel Husain's evals / llm-as-a-judge material). The agent's
prompt — `review-agent/review/reviewer.md` — is the product. Everything else is the machinery for developing it.

The refinement loop follows the judge-calibration recipe directly: one principal expert defines quality by
their written judgment; an LLM judge is calibrated against that judgment; the judge evaluates the system
under test; error analysis drives prompt changes; disagreements trend toward zero.

## Status

_Checkpoint 2026-08-21: the evaluation methodology has been settled and hardened; live refinement awaits
the maintainer's reactions._

**Done:**
- Phase 0 seed corpus: `data/corpus/` (gitignored cache) holds the closed agent PRs (414, 415, 417, 423, 424,
  427, 433), and the maintainer's 24 gold findings are classified.
- `review-agent/review/reviewer.md` drafted from the review signature extracted from those findings, and since extended:
  the boundary rule, attribution, checklist step, per-line anchoring, and a "record of acceptance
  reasoning" section (affirmation proportional to the reasoning actually established, checkable and
  reusable).
- Review packets: `corpus/build-packet.py` builds each PR's review inputs (snapshot + worktree at the
  reviewed commit, filtered by review round). Verified to make every gold-finding file reachable (24/24).
  A reachability guard excludes findings whose file is not in the snapshot.
- Harness hardened: per-PR failures surface in the aggregate ("N of M PRs evaluated") instead of silently
  producing 0/0; a failed matcher call surfaces, not retried; generation timeout is surfaced, not swallowed.
- All python-implemented judgments removed: `classify-comments.py` inference, `write-report.py` keyword
  clustering, and `validate.py` (deleted).
- PRs whose base branch was changed (`base_ref_changed`) are excluded at fetch.
- First durable measurement: ~67–68% recall across two runs, with previously unreachable findings
  recovered (415 TestHooks, 423 ClassLoader/codeBuilder, 424 all five, 427 test-file findings).

**Deliberate stand-ins:**
- The matcher (an LLM deciding whether a review comment captures a gold finding) is unvalidated: its
  numbers are reported as a matcher reading, not a measurement, until validated against the maintainer's
  reactions.
- The judge (`eval/judge.md`) is built and had an initial calibration on PR 439 (the "concessions are
  not blanket stances" and acceptance-reasoning refinements), but it is not yet trusted as a measurement;
  further calibration and the refine loop both require more reactions to the agent's posted reviews.

**Next step — the maintainer produces reactions:**
1. Review the agent's posted reviews in the normal GitHub flow: 👍/👎 reactions, "Question:"-prefixed
   replies, and their own review comments for anything missed.
2. React on the unposted (virtual) review findings too, so the matcher can be validated.
3. Then: validate the matcher, continue calibrating the judge, and run the first refinement step.

## Roles

| Role | What it is | Artifact |
|---|---|---|
| **Review agent** | The LLM that reads a PR (diff + repo context) and produces the review. This is the system under test. | `review-agent/review/reviewer.md` |
| **Judge** | The LLM that evaluates the *review agent's output* the way the expert would: per-comment `likely_disputed` / `likely_accepted` / `no_evidence`, plus a whole-review PASS / FAIL / REQUIRES REVIEW. Interpolates over the expert's demonstrated behavior (few-shot examples); a triage tool, not an authority. | `eval/judge.md` |
| **Domain expert** | The maintainer. Reviews are compared against their judgment. They react with emoji and write replies in the normal GitHub flow. Final authority. | — |

## Terminology

| Term | Meaning |
|---|---|
| **Reviewer prompt** | `review-agent/review/reviewer.md`. The prompt that defines the review agent. The product. |
| **File contents hash** | Hash of the reviewer prompt's contents, recorded in the review JSON's `prompt` field at generation time. It identifies the "prompt version" a review ran under; it is not stamped into posted comments, so it is not used for attribution. |
| **Anchor commit** | The head commit a review comment was made against = `original_commit_id`. Each anchor is the review-time state of the PR for the comments made against it. |
| **Base at review time** | The PR's fork point when a review comment was written. Recovered as `git merge-base(PR.base.sha, anchor)` (see below). |
| **Ground truth** | The expert's labels, derived from GitHub state: disputed (👎), confirmed (👍), question ("Question:"-prefixed reply), added (their own review comments). |

## The three workflows

The repository is organized around three workflows, each a directory under `review-agent/`. Each
meta-workflow refines the contents of the previous one: Eval improves the reviewer, and Calibrate improves
the instruments Eval uses.

- **Review** (`review/`) — given a PR, produce a review. The reviewer's prompt
  (`review-agent/review/reviewer.md`) is the product; `review-agent/review/post-review.py` is the only path from a review JSON to
  GitHub.
- **Eval** (`eval/`) — improve the reviewer. `eval/eval.py` measures it headlessly: it generates a
  virtual review per (PR, prompt), scores recall via the matcher (`eval/match_findings.py`), and writes
  the evidence (`eval/write-report.py`). `eval/refiner.md` is the propose-step discipline: read the
  report, draft ONE focused, evidence-cited patch to `review-agent/review/reviewer.md`, apply it on approval, and
  re-measure. The instruments Eval uses live here too — the judge (`eval/judge.md`, run by
  `eval/run-judge.py`) for precision on virtual reviews once calibrated, and the matcher.
- **Calibrate** (`calibrate/`) — keep the instruments trustworthy by checking them against the expert's
  reactions. `calibrate/harvest-comments.py` mines the expert's comments as few-shot material for the
  judge; `calibrate/measure-agreement.py` runs the judge on a posted review and compares its verdicts to
  the expert's labels (the calibration score); `calibrate/validate-matcher.py` prints the matcher's
  captured/missed pairs so the matcher's verdicts can be checked before its recall numbers are trusted.
  Disagreements from these tools are the signal for adjusting `eval/judge.md`.

The corpus maintenance scripts (`corpus/`) are a supporting step shared by Eval and Calibrate:
`corpus/fetch-prs.sh` pulls PR data from GitHub, and `corpus/classify-comments.py` derives ground-truth
labels from the expert's reactions. GitHub is the system of record; `data/` is a recreatable local cache.

When running these workflows, treat any missing capability as a gap in the tooling: extend the harness
itself rather than improvising a one-off script. The harness is the product's machinery, and a permanent
improvement makes the next run cheaper.

Long-running steps (a review generation, a judge verdict, a matcher call) take minutes each and should be
launched in the background rather than run blocking in the foreground: start the tool with `nohup`, log to
a session directory, and gauge progress by tailing the aggregate log or the per-PR generation logs under
`data/eval/<key>/`. The tools print a line per PR as each one completes.

Eval runs do not destroy their predecessors: before a PR is regenerated, its current artifacts (review, log,
match results, stats) move to `data/eval/archive-<ts>/<key>/`, so successive runs of the same prompt stay
comparable and a timed-out generation's log survives its retry. Each generation also writes
`<PR>.stats.json` — duration, token usage (input/output/reasoning/cache), cost, tool calls, tokens/sec, and
assistant-turn latency, plus the packet's size — so variance in generation time can be diagnosed without
parsing the raw log. When a regeneration takes more than 2x longer than the previous one for the same PR,
the eval flags it as a warning in both the run output and the report: a large slowdown is a signal worth
investigating (model degradation or rate limiting), not something to absorb silently.

## The loop

### Day-to-day (generation — manual, out of scope)

1. An agent PR opens. The maintainer opens an opencode window and asks the agent to review the PR.
2. The review agent reads `review-agent/review/reviewer.md`, reviews the PR, and writes the review as a JSON document to a
   path; the maintainer previews or edits it, and `review-agent/review/post-review.py` posts it under the `prdoyle-agent`
   account.
3. The maintainer engages in the normal GitHub flow: 👍 for agreement, 👎 for disagreement, "Question:"
   -prefixed replies to mark something as a question, and their own review comments for anything missed.
   Silence means unjudged.
4. Every ~5th PR is reviewed cold first (the review withheld until after the maintainer's own) so that
   "what the review missed" is judged independently.
5. Closed PRs are the settled data. Threaded replies — the author's or anyone else's — are never review
   comments; only top-level comments count.

### On-demand refinement (what we build)

The maintainer runs the runner whenever they want a step (a few new closed PRs, or reviews feel off).
One run produces **one proposed patch** to `review-agent/review/reviewer.md`, with the evidence, for approval.

## Evaluation foundations

- **Signal from closed PRs only.** Open PRs are in flux; closed means the conversation has settled.
- **Classification is a pure function of GitHub state** (emoji + reply body + comment presence), recomputed
  fresh each run. GitHub is the system of record; there is no local overrides file.
- **Label priority per comment**, first match wins:
  1. **Emoji** — 👎 → disputed, 👍 → confirmed. The expert only uses thumb emoji to agree or disagree, so
     this is authoritative and trumps any text.
  2. **"Question:" prefix** on the expert's reply — **question** (a soft clarity signal, counted as
     neither dispute nor confirm).
  3. **Unjudged** — no reply and no emoji: omitted from the metrics entirely. A dispute the expert signals
     neither by 👎 nor by reply is lost signal, visible only in the raw threads.
- **Added** — the expert's own review comments (not replies) are missed findings: the recall signal.
- **Disagreement = disputed + added**, as counts per PR. Confirmed and question are reported alongside for
  context. Counts, not a rate: a rate over `disputed + confirmed` degenerates when the expert 👍-sparingly.
- **Both axes always tracked**: precision (disputed comments) and recall (added findings). A silent review
  trivially scores zero disagreements, so coverage is always measured too.
- **Offline eval is recall-first.** `eval.py` generates virtual reviews (unposted review JSON) per
  (PR, prompt) via `opencode run` and scores recall against the expert's gold findings. Matching is
  semantic: an LLM matcher decides whether a review comment captures a gold finding, because string
  similarity misses reworded matches. The matcher's verdicts are not trusted as a measurement until
  validated against the expert's reactions; until then, recall is an unvalidated matcher
  reading. Precision on virtual reviews is judged by the review judge (run-judge.py), since they have no
  expert reactions; the judge has had an initial calibration on PR 439 and is not yet trusted as a
  measurement.
- **Attribution by marker.** The reviewer and the PR author both post under the reviewer account
  (`prdoyle-agent`), so the account alone cannot tell them apart. The `[review]` marker is applied by
  `review-agent/review/post-review.py` to review comments and by `review-agent/review/post-responses.py`
  to replies, so it no longer distinguishes them: review comments are the top-level ones, and threaded
  replies are never review comments, marked or not. Author comments carry no marker. The review JSON's
  `prompt` field records the prompt version that
  produced the review at generation time — useful because a timestamp is unreliable: a long-lived
  opencode window may cache an older prompt, or a review may run against an edited-but-uncommitted prompt.
  It is not stamped into the posted comments, so a posted review's prompt version is not recoverable from
  GitHub alone.
- **Commit pinning.** Every comment carries `original_commit_id` (the creation-time head anchor). The judge
  evaluates with the same commits the review used.
- **Base at review time is recoverable** from the clone: `git merge-base(PR.base.sha, anchor)` gives the
  PR's fork point at review time, and `git diff <that> <anchor>` reproduces the review-time diff for the
  comments made against that anchor. This works for merged, rebased, and squash-merged PRs alike, because
  `PR.base.sha` is GitHub's record of the base tip at review time. For a non-merged (open or closed-without-
  merge) PR, there are no review anchors, and the change set is the local clone's `git diff` from the fork
  point to the reviewed head — the same commit the worktree is pinned to. Known limitation: a
  force-pushed `main` can confuse reconstruction — accepted as tolerable (rare, and the reachability guard
  contains the damage).
- **Review packets.** The reviewer's inputs are a *review packet* built by `corpus/build-packet.py`: a
  snapshot of the PR's GitHub-visible information (title, description, changed files, the per-anchor
  review-time diffs `git diff $(git merge-base(PR.base.sha, anchor))..anchor`, and the comments and
  reactions present at the review-request moment — each comment carrying its anchored record:
  `original_line` (or the span `original_start_line`..`original_line`) within its `diff_hunk`, plus
  `original_commit_id` — filtered by review round) plus a fresh worktree at the
  commit being reviewed. The same packet procedure is used in production (current state) and in the eval
  (round N). In the eval the generation runs without GitHub credentials, so the agent cannot read the PR's
  review thread — which contains the gold findings it is scored against. A finding whose file is not in
  the snapshot's changed-file list is unreachable and excluded from the denominator (the reachability
  guard).
- **Base-branch changes.** A PR whose base branch changed (`base_ref_changed`) is excluded at fetch:
  `merge-base(PR.base.sha, anchor)` would be meaningless for its early comments. A PR that merged changes
  from main into its branch is *not* a problem: GitHub's own diff semantics treat the merged-in content as
  context, and `merge-base(PR.base.sha, anchor)` matches that.
- **Whole-review judge granularity.** The judge input (diff + repo contents) is potentially enormous and
  each comment is a function of all of it, so the judge renders per-comment verdicts plus one whole-review
  verdict, free to name specific comments. It is given the same context the reviewer had (same clone, same
  commits) plus the review being evaluated.
- **The judge knows its limits.** The judge does not know the expert; it interpolates over the
  few-shot examples of the expert's real critiques embedded in `eval/judge.md`. On comment types those
  examples do not cover it says `no_evidence` rather than guessing, and the review is marked REQUIRES
  REVIEW so the expert eyeballs those comments. The judge is a triage tool: its `likely_disputed` and
  `no_evidence` verdicts are flags for the expert, whose reaction is the verdict that counts.
- **Judge calibration.** The judge's verdicts are iterated against the expert's finalized labels
  (precision/recall vs. the expert's disputes, confirms, and added findings). It is recalibrated only
  when the expert corrects its verdicts, or after a material change to the review agent.
- **No charts.** The report is evidence for the current step, not a dashboard. The maintainer decides when
  to run; drift is felt before it is measured.
- **Corpus is a cache.** `data/` is gitignored. Two kinds of thing live there, with different natures:
  `data/corpus/` is *reference data* — PR metadata, comments, reactions, classifications, and the
  git-derived review-time contexts — losslessly recreatable from GitHub plus the repo's git history.
  `data/eval/` holds *run artifacts* (virtual reviews, match results, logs), keyed by prompt hash and
  model so prompt versions can be compared; they are regenerable, but not losslessly recreatable, since
  generation is stochastic. Only `review/`, `eval/`, `calibrate/`, and `corpus/` under `review-agent/` (and
  this plan) are committed.

## Deliverables

```
review-agent/
  PLAN.md
  review/                  # workflow: Review
    reviewer.md            # the reviewer role's prompt — the product
    post-review.py         # the only path from a review JSON to GitHub: posts it as one review
    post-responses.py      # the only path from a responses JSON to GitHub: replies + thread resolutions
  eval/                    # workflow: Eval — improve the reviewer
    eval.py                # whole-workflow runner: review packets, headless generation, recall
    run-loop.sh            # the measurement loop in one command: fetch, classify, eval, report, calibrate
    show-log.py            # render a generation log as readable activity for monitoring
    write-report.py        # evidence report: per-PR counts, raw threads, wall-of-text flag
    refiner.md             # propose-step discipline (one focused change, evidence-cited, voice-preserving)
    judge.md               # judge prompt — the instrument (initially calibrated on PR 439)
    run-judge.py           # run the judge on a review JSON (triage)
    match_findings.py      # the recall matcher (imported by eval.py)
    analyze.py             # post hoc analysis: stats, run comparison, outside-packet audit, in-flight monitor
  calibrate/               # workflow: Calibrate — keep the instruments trustworthy
    harvest-comments.py    # mine the maintainer's comments as few-shot material
    measure-agreement.py   # judge verdicts vs the expert's labels: the calibration score
    validate-matcher.py    # print the matcher's captured/missed pairs for the maintainer to check
  corpus/                  # maintains the corpus
    fetch-prs.sh           # closed agent PRs, excluding base_ref_changed: metadata, comments, reactions
    classify-comments.py   # labels: emoji / "Question:" / unjudged → ground truth
    build-packet.py        # the reviewer's inputs: snapshot + worktree at the reviewed commit
    reconstruct-review.py  # rebuild a posted review's JSON from the corpus data
  tests/                   # hermetic unit tests on the tooling's pure cores
  data/                    # gitignored
    corpus/<PR>/…          # reference data: metadata, comments, reactions, classifications, contexts
    eval/<key>/<PR>.{json,log,stats,match*}   # run artifacts: virtual reviews, generation logs and stats, match results
    eval/archive-<ts>/<key>/…                 # superseded generations, preserved for post hoc analysis
    reports/               # evidence output
```

The review agent's output is a JSON document — `{summary, verdict, prompt, comments: [{path, line, body}]}` —
written to a path, never posted directly. `review-agent/review/post-review.py` is the only path to GitHub; `eval.py`
consumes the same JSON without posting. A review JSON that is never posted is a *virtual review*. The response cycle
follows the same rule: the agent reads the current threads from the packet and writes its decisions as a responses
JSON, which `review-agent/review/post-responses.py` posts.

## Testing

The review tooling has never had a test suite, and the review that introduced it surfaced a cluster of bugs an
automated suite would have caught. The bugs fall into four types, each with a distinct remedy:

1. **Pure-logic correctness** — for example the classify label priority and the round filter in build-packet.
   Unit tests on the logic catch these.
2. **Data-shape drift** — for example the packet missing `original_commit_id` and the gratuitous field renames.
   The code worked; the shape was just wrong. Golden tests on the packet, plus contract tests between the
   skill's JSON specs and what the posting scripts validate, catch these. The packet is doubly important because
   it feeds both production reviews and the eval, so a wrong shape corrupts both.
3. **Dead code and awkward idioms** — for example an unused map in classify. Lint (pyflakes) catches these.
4. **Prompt design** — for example the judge's exposure to prompt injection and the comment-anchor wording. Not
   unit-testable; the eval is the test for the prompt products (`reviewer.md`, `judge.md`).

### Structure: functional core, imperative shell

The recommended structure is functional core / imperative shell: a core of pure functions that take input as
parameters and return output, with no I/O or side effects, and a thin shell that does all I/O with the minimum
possible logic. This is the structural form of the CLAUDE.md principle "separate complex logic from side effects
to facilitate unit testing". It applies where the logic is non-trivial and bug-prone:

- `corpus/build-packet.py` — core `build_snapshot(comments, diffs, pr, boundary, reactions)`; shell reads the
  corpus and runs git.
- `corpus/classify-comments.py` — core `classify(comments, reactions, reviews, pr_meta)`; shell reads the corpus
  and writes `classification.json`.
- `review/post-review.py`, `review/post-responses.py` — cores `validate_review(doc)` and `validate_responses(doc)`
  (plus the comment-to-thread mapping); shells run gh.
- `corpus/reconstruct-review.py` — core `reconstruct(comments, rvs)`; shell reads and writes.

It does not apply to the prompt products (eval-tested, not unit-tested), to already-pure shells (`fetch-prs.sh`,
`run-loop.sh`), or to mostly-I/O orchestration (`eval.py`, the calibrate tools) whose pure core would be a few
lines. The split is mechanical and behavior-preserving, and can be verified by diffing output before and after
against the existing corpus.

### Recommended test suite

Hermetic unit tests under `review-agent/tests/` with small committed fixtures; no live GitHub, so deterministic
and CI-able.

- build-packet: golden snapshot output; round-boundary behavior; the required comment-field set (a regression
  test for `original_commit_id`).
- classify: label priority (emoji authoritative, "Question:" overrides, unjudged).
- post-review / post-responses: accept valid and reject invalid review and responses JSON, the contract with
  the skill's output formats.
- match_findings: `extract_json` parses the last JSON object out of noisy output.
- Lint (pyflakes) for dead code, plus a mutation-style meta-check (remove a required packet field; the golden
  test must fail) so the tests cannot go stale.

What is not unit-tested: the shells, which are thin, mechanical, and already exercised by the eval and by real
runs, and the prompt products, which the eval covers. A hermetic smoke test of build-packet against a fixture git
repo (a merged PR, so no GitHub access is needed) is a possible future addition.

### Open decisions

- Framework: pytest (readable, parametrized; a new dependency for this directory) vs stdlib `unittest` (zero
  dependencies). pytest is preferred.
- CI wiring: a GitHub Actions job (lint and tests) vs a local `review-agent/run-tests.sh`.
- Scope: whether to include linting.
- Whether the FCIS restructure and the tests are separate commits. The recommendation is a behavior-preserving
  refactoring commit first, then the tests.

## Phases

### Phase 0 — Seed corpus and reviewer prompt v1 (done)

- Fetch the 7 reviewed PRs (414, 415, 417, 423, 424, 427, 433) and the maintainer's 24 comments.
- Classify the comments into themes (convention compliance, design questions, test quality, terminology,
  comment placement and verbosity, naming, diagnostics) and extract the review signature: voice, question
  phrasing, density, priorities, blocking signal.
- Draft `review-agent/review/reviewer.md` v1 from that signature (role, process, priorities, output format, anti-patterns,
  few-shot examples, boundary rule).
- These PRs predate the review agent, so they supply gold *findings* (style + recall baseline) but no
  dispute data; the judge received its initial calibration on PR 439 and continues to accrue calibration
  data from live review cycles.

### Phase 1 — The refinement runner (partly built)

The measurement machinery exists: fetch → classify → report → eval. The loop that turns measurement into
prompt changes is still to be closed, and depends on the maintainer's reactions. One run is one step,
run on demand in an opencode window:

1. **fetch** — closed agent PRs lacking corpus records, excluding any whose base branch changed: PR
   metadata, reviews, review comments (`original_commit_id`, reactions, `html_url`), and the maintainer's
   added review comments.
2. **classify** — attribute only the top-level comments as review comments: threaded replies, marked or
   not, are never review comments; apply the label priority above.
3. **report** — the evidence: per-PR disputed / confirmed / question / added / unjudged counts and the raw
   threads.
4. **eval** (as needed) — regenerate virtual reviews against per-anchor review-time contexts; report
   recall of the added findings via the matcher, with the reachability guard.
5. **validate the matcher** — run `calibrate/validate-matcher.py` on PRs the maintainer has engaged with
   and eyeball the captured/missed pairs; the recall numbers are not trusted until then.
6. **calibrate judge** (only when needed) — run `calibrate/measure-agreement.py` on a reviewed PR and
   compare the judge's verdicts to the expert's finalized labels; iterate `eval/judge.md` (and its few-shot
   examples, refreshed by `calibrate/harvest-comments.py`) until agreement is high.
7. **propose** — an agent reads the report + `review-agent/review/reviewer.md` and drafts ONE focused patch for the
   dominant failure mode, with cited evidence and rationale (`eval/refiner.md`).
8. **apply** — on approval the patch lands; subsequent reviews run under the new prompt.

### Phase 2 — Usage

The two modes above. Orchestration is an opencode skill wrapping the tools (so the `propose` step
is conversational), invoked on demand. No scheduler.

### Phase 3 — Success criteria

Disagreements (disputed + added) per PR trend down over time; recall stays high; converged
= the maintainer stops feeling the need to correct reviews, and validation keeps passing. Agreement is judged
by the expert, not a number.

## Known limitations (accepted)

- A force-pushed `main` can confuse base reconstruction. The reachability guard contains the damage, but
  the error is undetectable at fetch time.
- A dispute signaled neither by 👎 nor by a reply is lost signal, visible only in the raw threads.
- A PR whose base branch changed is dropped entirely, not partially reconstructed.
- The seed corpus cannot fully calibrate the judge; an initial calibration was done on PR 439, and further
  calibration awaits more reactions to the agent's posted reviews.
- Reviews the expert never reads (nor reacts to) contribute nothing — silence is genuinely ambiguous and is
  deliberately treated as unjudged rather than accepted.
- Generation can be slow (a review is an agentic loop: the model reads the diff and the surrounding files).
  A generation that times out surfaces as an unevaluated PR, never as a silent 0/0.
