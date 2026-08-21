# Plan: A self-calibrating PR-review assistant

## Goal

Build an **AI review agent** that produces PR reviews indistinguishable from those the maintainer of this
repository would write, plus an **on-demand refinement loop** that improves the agent over time using the
methods from the LLM-evaluation literature (Hamel Husain's evals / llm-as-a-judge material). The agent's
prompt — `prompts/review.md` — is the product. Everything else is the machinery for developing it.

The refinement loop follows the judge-calibration recipe directly: one principal expert defines quality by
their written judgment; an LLM judge is calibrated against that judgment; the judge evaluates the system
under test; error analysis drives prompt changes; disagreements trend toward zero.

## Status

_Checkpoint 2026-08-21: the evaluation methodology has been settled and hardened; live refinement awaits
the maintainer's reactions._

**Done:**
- Phase 0 seed corpus: `data/corpus/` (gitignored cache) holds the closed agent PRs (414, 415, 417, 423, 424,
  427, 433), and the maintainer's 24 gold findings are classified.
- `prompts/review.md` drafted from the review signature extracted from those findings, and since extended:
  the boundary rule, attribution, checklist step, per-line anchoring, and a "record of acceptance
  reasoning" section (affirmation proportional to the reasoning actually established, checkable and
  reusable).
- Review-time context reconstruction: `eval.py` gives the agent one section per review anchor, each
  `git diff $(git merge-base(PR.base.sha, anchor))..anchor`. Verified to make every gold-finding file
  reachable (24/24). A reachability guard excludes findings whose file is not in the context.
- Harness hardened: per-PR failures surface in the aggregate ("N of M PRs evaluated") instead of silently
  producing 0/0; the matcher retries once; generation timeout is surfaced, not swallowed.
- All python-implemented judgments removed: `classify.py` inference, `report.py` keyword clustering, and
  `validate.py` (deleted).
- PRs whose base branch was changed (`base_ref_changed`) are excluded at fetch.
- First durable measurement: ~67–68% recall across two runs, with previously unreachable findings
  recovered (415 TestHooks, 423 ClassLoader/codeBuilder, 424 all five, 427 test-file findings).

**Deliberate stand-ins:**
- The matcher (an LLM deciding whether a review comment captures a gold finding) is unvalidated: its
  numbers are reported as a matcher reading, not a measurement, until validated against the maintainer's
  reactions.
- The judge (`prompts/judge.md`) is built but uncalibrated; judge calibration and the refine loop both
  require live review cycles — the seed corpus has no review-agent comments to react to.

**Next step — the maintainer produces reactions:**
1. Review the agent's posted reviews in the normal GitHub flow: 👍/👎 reactions, "Question:"-prefixed
   replies, and their own review comments for anything missed.
2. React on the unposted (virtual) review findings too, so the matcher can be validated.
3. Then: validate the matcher, calibrate the judge, and run the first refinement step.

## Roles

| Role | What it is | Artifact |
|---|---|---|
| **Review agent** | The LLM that reads a PR (diff + repo context) and produces the review. This is the system under test. | `prompts/review.md` |
| **Judge** | The LLM that evaluates the *review agent's output* the way the expert would: per-comment `likely_disputed` / `likely_accepted` / `no_evidence`, plus a whole-review PASS / FAIL / REQUIRES REVIEW. Interpolates over the expert's demonstrated behavior (few-shot examples); a triage tool, not an authority. | `prompts/judge.md` |
| **Domain expert** | The maintainer. Reviews are compared against their judgment. They react with emoji and write replies in the normal GitHub flow. Final authority. | — |

## Terminology

| Term | Meaning |
|---|---|
| **Review prompt** | `prompts/review.md`. The prompt that defines the review agent. The product. |
| **File contents hash** | Hash of the review prompt's contents, recorded in the review JSON's `prompt` field. It identifies the "prompt version" a review ran under; not required for attribution. |
| **Anchor commit** | The head commit a review comment was made against = `original_commit_id`. Each anchor is the review-time state of the PR for the comments made against it. |
| **Base at review time** | The PR's fork point when a review comment was written. Recovered as `git merge-base(PR.base.sha, anchor)` (see below). |
| **Ground truth** | The expert's labels, derived from GitHub state: disputed (👎), confirmed (👍), question ("Question:"-prefixed reply), added (their own review comments). |

## The loop

### Day-to-day (generation — manual, out of scope)

1. An agent PR opens. The maintainer opens an opencode window and asks the agent to review the PR.
2. The review agent reads `prompts/review.md`, reviews the PR, and writes the review as a JSON document to a
   path; the maintainer previews or edits it, and `post_review.py` posts it under the `prdoyle-agent`
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
One run produces **one proposed patch** to `prompts/review.md`, with the evidence, for approval.

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
  reading. Precision on virtual reviews would need the calibrated judge, since they have no expert
  reactions, so it is deferred to the judge phase.
- **Attribution by reviewer account.** The review agent posts under its reviewer account
  (`prdoyle-agent`), and its review comments are the only top-level comments that account makes, so the
  account identifies them regardless of who authored the PR. Threaded replies are never review comments.
  Attribution therefore does not depend on a marker. `post_review.py` prepends `[review]` to each posted
  comment so the agent's comments are visually distinguishable from the author's on GitHub. The review
  JSON's `prompt` field records the prompt version that produced the review — useful because a
  timestamp is unreliable: a long-lived opencode window may cache an older prompt, or a review may run
  against an edited-but-uncommitted prompt.
- **Commit pinning.** Every comment carries `original_commit_id` (the creation-time head anchor). The judge
  evaluates with the same commits the review used.
- **Base at review time is recoverable** from the clone: `git merge-base(PR.base.sha, anchor)` gives the
  PR's fork point at review time, and `git diff <that> <anchor>` reproduces the review-time diff for the
  comments made against that anchor. This works for merged, rebased, and squash-merged PRs alike, because
  `PR.base.sha` is GitHub's record of the base tip at review time. For a non-merged (open or closed-without-
  merge) PR, there are no review anchors, and `gh pr diff` gives the change set. Known limitation: a
  force-pushed `main` can confuse reconstruction — accepted as tolerable (rare, and the reachability guard
  contains the damage).
- **Review-time context.** `eval.py` hands the agent one section per review anchor (each
  `git diff $(git merge-base(PR.base.sha, anchor))..anchor`), with the anchor commit identified so the
  agent reads the review-time version of the files. A finding whose file is not present in any section's
  diff is unreachable and excluded from the denominator (the reachability guard).
- **Base-branch changes.** A PR whose base branch changed (`base_ref_changed`) is excluded at fetch:
  `merge-base(PR.base.sha, anchor)` would be meaningless for its early comments. A PR that merged changes
  from main into its branch is *not* a problem: GitHub's own diff semantics treat the merged-in content as
  context, and `merge-base(PR.base.sha, anchor)` matches that.
- **Whole-review judge granularity.** The judge input (diff + repo contents) is potentially enormous and
  each comment is a function of all of it, so the judge renders per-comment verdicts plus one whole-review
  verdict, free to name specific comments. It is given the same context the reviewer had (same clone, same
  commits) plus the review being evaluated.
- **The judge knows its limits.** The judge does not know the expert; it interpolates over the
  few-shot examples of the expert's real critiques embedded in `prompts/judge.md`. On comment types those
  examples do not cover it says `no_evidence` rather than guessing, and the review is marked REQUIRES
  REVIEW so the expert eyeballs those comments. The judge is a triage tool: its `likely_disputed` and
  `no_evidence` verdicts are flags for the expert, whose reaction is the verdict that counts.
- **Judge calibration.** The judge's verdicts are iterated against the expert's finalized labels
  (precision/recall vs. the expert's disputes, confirms, and added findings). It is recalibrated only
  when the expert corrects its verdicts, or after a material change to the review agent.
- **No charts.** The report is evidence for the current step, not a dashboard. The maintainer decides when
  to run; drift is felt before it is measured.
- **Corpus is a cache.** `data/` is gitignored and fully reconstructable from GitHub plus
  the repo's git history. Only `prompts/` and `tools/` (and this plan) are committed.

## Deliverables

```
review/
  PLAN.md
  prompts/
    review.md        # review prompt — the product
    judge.md         # judge prompt — the instrument (uncalibrated)
    refine.md        # propose-step discipline (one focused change, evidence-cited, voice-preserving)
  tools/
    fetch_closed.sh  # closed agent PRs, excluding base_ref_changed: metadata, comments, reactions
    classify.py      # labels: emoji / "Question:" / unjudged → ground truth
    report.py        # evidence report: per-PR counts, raw threads, wall-of-text flag
    post_review.py   # the only path from a review JSON to GitHub: posts it as one review
    eval.py          # per-anchor review-time contexts + headless generation + recall via LLM matcher
    judge.py         # run the judge on a review JSON (triage)
    harvest_comments.py  # harvest a maintainer's review comments for examples
  data/
    corpus/<PR>/…    # gitignored cache
    reports/         # gitignored evidence output
```

The review agent's output is a JSON document — `{summary, verdict, prompt, comments: [{path, line, body}]}` —
written to a path, never posted directly. `post_review.py` is the only path to GitHub; `eval.py` consumes
the same JSON without posting. A review JSON that is never posted is a *virtual review*.

## Phases

### Phase 0 — Seed corpus and review prompt v1 (done)

- Fetch the 7 reviewed PRs (414, 415, 417, 423, 424, 427, 433) and the maintainer's 24 comments.
- Classify the comments into themes (convention compliance, design questions, test quality, terminology,
  comment placement and verbosity, naming, diagnostics) and extract the review signature: voice, question
  phrasing, density, priorities, blocking signal.
- Draft `prompts/review.md` v1 from that signature (role, process, priorities, output format, anti-patterns,
  few-shot examples, boundary rule).
- These PRs predate the review agent, so they supply gold *findings* (style + recall baseline) but no
  dispute data; the judge accrues calibration data from the first live review cycles.

### Phase 1 — The refinement runner (partly built)

The measurement machinery exists: fetch → classify → report → eval. The loop that turns measurement into
prompt changes is still to be closed, and depends on the maintainer's reactions. One run is one step,
run on demand in an opencode window:

1. **fetch** — closed agent PRs lacking corpus records, excluding any whose base branch changed: PR
   metadata, reviews, review comments (`original_commit_id`, reactions, `html_url`), and the maintainer's
   added review comments.
2. **classify** — attribute comments by account (top-level author-account comments are the review); apply
   the label priority above.
3. **report** — the evidence: per-PR disputed / confirmed / question / added / unjudged counts and the raw
   threads.
4. **eval** (as needed) — regenerate virtual reviews against per-anchor review-time contexts; report
   recall of the added findings via the matcher, with the reachability guard. `harvest_comments.py` mines
   the maintainer's comments as few-shot material.
5. **validate the matcher** — compare its verdicts against the maintainer's reactions on enough PRs; the
   recall numbers are not trusted until then.
6. **calibrate judge** (only when needed) — iterate `prompts/judge.md` until its whole-review pass/fail
   predicts the expert's finalized labels.
7. **propose** — an agent reads the report + `prompts/review.md` and drafts ONE focused patch for the
   dominant failure mode, with cited evidence and rationale (`prompts/refine.md`).
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
- The seed corpus cannot calibrate the judge until live review cycles accumulate.
- Reviews the expert never reads (nor reacts to) contribute nothing — silence is genuinely ambiguous and is
  deliberately treated as unjudged rather than accepted.
- Generation can be slow (a review is an agentic loop: the model reads the diff and the surrounding files).
  A generation that times out surfaces as an unevaluated PR, never as a silent 0/0.
