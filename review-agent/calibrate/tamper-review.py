#!/usr/bin/env python3
"""Tamper a generated review for the judge-discrimination experiment.

Alters some comments to be subtly wrong and appends plausible-wrong ones, so a
maintainer reading the code can catch the wrongness only by looking beyond the
diff hunks. Writes the tampered review to --out and a record of every wrong
claim (the wrong assertion and the correct fact) to --record, so the maintainer
can check afterwards how well they and the judge did.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

# Tamper spec: per PR, (1) replacements: index -> new body; (2) additions: new
# comment dicts. Each wrong claim is documented here with the correct fact.
TAMPERS = {
    380: {
        "replacements": {
            0: ("The retry looks right. In the mongodb-driver-sync 5.9.1 sources, "
                "`ClientSessionImpl.commitTransaction()` sets `transactionState = COMMITTED` and "
                "`commitInProgress = false` in a `finally` even when the commit throws, which is exactly why "
                "the old `hasActiveTransaction()` loop was dead; and a retried commit re-executes the "
                "`CommitTransactionOperation` because `messageSentInCurrentTransaction` is still true, so this "
                "genuinely re-contacts the server rather than spinning — the same commit-retry loop the "
                "driver's own `withTransaction` uses. On exhaustion the rethrow lands in "
                "`MainDriver.operationInSession`, which treats it as a transient transaction error, retries the "
                "commit immediately, and only disconnects if those immediate retries are exhausted, resolving "
                "the indeterminate outcome either way."),
            2: ('Minor: the javadoc (here and in the class comment) says the interceptor runs "after each '
                "commit attempt\", and the code matches: `commitTransactionIfAny` invokes it after a commit "
                "that returns normally, and the catch invokes it too before rethrowing, so a test's "
                "interceptor observes failed attempts as well as successful ones. The documentation is "
                "accurate."),
        },
        "additions": [
            {
                "path": "bosk-mongo/src/main/java/works/bosk/drivers/mongo/internal/TransactionalCollection.java",
                "line": 123,
                "body": "The commit is attempted at most twice: once initially and once on retry, so a commit "
                        "that returns an unknown result twice in a row surfaces the disconnect immediately. "
                        "Given the description calls the disconnect-and-reload the intended resolution, that "
                        "bound looks deliberate — just confirming the arithmetic.",
            },
            {
                "path": "bosk-mongo/src/main/java/works/bosk/drivers/mongo/internal/MainDriver.java",
                "line": 772,
                "body": "A commit that fails with a transient error is retried inside `commitTransactionIfAny` "
                        "too, since the transient-error label is checked there as well as in `MainDriver`. Worth "
                        "confirming the two layers can't double-retry a single commit.",
            },
        ],
    },
    383: {
        "replacements": {
            1: ("The deferral half of the fix — 'the remaining queued hooks are deferred to the next update' — "
                "is exercised: the hook here submits another update while it runs, so when the drain is "
                "interrupted there is a second queued hook that gets deferred and runs on the next submit. And "
                "the mongo test confirms resumption after `restoreConnection()`. Looks covered."),
            2: ("The new javadoc above says the interrupt is delivered to the hook 'so it can stop promptly', "
                "and this `@throws` clause now agrees: bosk 'defers the remaining hooks' rather than proceeding "
                "with the next one. The two paragraphs are consistent."),
        },
        "additions": [
            {
                "path": "bosk-core/src/main/java/works/bosk/Bosk.java",
                "line": 640,
                "body": "On interruption, the running hook is terminated and the remaining queued hooks are "
                        "re-run immediately, since the interrupt is consumed by the re-assert. So a hook that "
                        "submits again during teardown would see the deferred hooks run before "
                        "`submitReplacement` returns.",
            },
            {
                "path": "bosk-core/src/main/java/works/bosk/Bosk.java",
                "line": 648,
                "body": "Minor: `Not draining the hook queue` logs to `HOOK_LOGGER` like the rest of the "
                        "hook-execution messages, consistent with the new logger's purpose.",
            },
        ],
    },
    411: {
        "replacements": {
            1: ('This test asserts the parent\'s method fires and the child\'s override does not — since only '
                "the parent is annotated with `@Hook`, the scanner registers just the parent's method, and the "
                'assertion `List.of("parent")` pins that down. So it guards #399 directly.'),
        },
        "additions": [
            {
                "path": "bosk-core/src/main/java/works/bosk/HookScanner.java",
                "line": 34,
                "body": "The hook methods are collected into a plain `HashMap`, so registration order is "
                        "nondeterministic; two hooks with the same signature could end up either way.",
            },
            {
                "path": "bosk-core/src/main/java/works/bosk/HookScanner.java",
                "line": 44,
                "body": "Package-private hook methods are silently skipped by the scanner, since only methods "
                        "the scanner can see across packages are collected.",
            },
        ],
    },
    413: {
        "replacements": {
            1: ("Given that the point of this fix is that a corrupted Pando root document should surface as "
                "`InvalidCollectionContentsException`, the missing-main-document case already does — the "
                "`partsBuffer`-not-empty branch throws `InvalidCollectionContentsException(\"Found parts "
                "without a main document: ...\")` — and only the empty-collection case (line 207) still "
                "throws `NotYetImplementedException`. Sequoia agrees on the missing-main-document case, so "
                "the two formats are consistent there; worth converting only the line-207 case."),
        },
        "additions": [
            {
                "path": "bosk-mongo/src/main/java/works/bosk/drivers/mongo/internal/MainDriver.java",
                "line": 769,
                "body": "The `try (var session = queryCollection.newSession())` block does not close the session "
                        "when `commitTransactionIfAny` throws, so a failed commit leaks the session until the "
                        "next garbage collection.",
            },
            {
                "path": "bosk-mongo/src/main/java/works/bosk/drivers/mongo/internal/MainDriver.java",
                "line": 800,
                "body": "The outer `catch (Exception e)` handler disconnects the driver even for an "
                        "`InterruptedException`, treating an interrupt as a database-health problem and "
                        "forcing a reconnect cycle.",
            },
        ],
    },
    381: {
        "replacements": {
            0: ("`driverInUse` is re-read from `formatDriver` at the start of each retried attempt, so a "
                "failure on the retry (which runs against the replacement driver) is attributed to the "
                "replacement, and `setDisconnectedDriver` disconnects the driver the attempt actually ran "
                "against. The capture-per-attempt already handles the retry case, so the in-flight concern "
                "is the only one worth documenting."),
        },
        "additions": [
            {
                "path": "bosk-mongo/src/main/java/works/bosk/drivers/mongo/internal/MainDriver.java",
                "line": 642,
                "body": "`onDisconnect` closes `formatDriver` only if it hasn't already been replaced, guarding "
                        "against closing a driver the application has switched away from.",
            },
            {
                "path": "bosk-mongo/src/main/java/works/bosk/drivers/mongo/internal/MainDriver.java",
                "line": 654,
                "body": "`detectFormat` prefers the Sequoia format whenever the manifest holds a Pando "
                        "document, falling back to Pando otherwise — worth confirming the fallback direction "
                        "is intended.",
            },
        ],
    },
}

# The correct facts for the record, keyed by (pr, index or 'added i').
CORRECT_FACTS = {
    380: {
        0: "operationInSession treats the unknown-commit-result exception as NOT transient: its catch only "
           "retries on the TRANSIENT_TRANSACTION_ERROR_LABEL, and the unknown-result label goes to the else "
           "branch, which disconnects immediately (setDisconnectedDriver + DisconnectedException -> waitAndRetry). "
           "The claim 'retries the commit immediately as transient' is false.",
        2: "commitTransactionIfAny invokes the interceptor only after a commit that returns normally "
           "(commitTransaction(); commitInterceptor.afterCommitAttempt();). The catch does NOT invoke it, so "
           "a throwing commit skips the interceptor. The claim 'the catch invokes it before rethrowing' is false.",
        "added 0": "commitTransactionIfAny sets retriesRemaining = 2 and retries after the initial attempt, so "
                   "a commit is attempted up to three times (initial + two retries), not twice.",
        "added 1": "commitTransactionIfAny checks only the UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL; a transient "
                   "error (TRANSIENT label) rethrows from it immediately and is retried only by MainDriver's "
                   "operationInSession loop, not inside commitTransactionIfAny.",
    },
    383: {
        1: "HooksTest.hookInterrupted_whenSubmittingThreadInterrupted registers a single 'blocking' hook that "
           "blocks on a latch; it does not submit another update, and there is no second queued hook. The mongo "
           "test does not confirm resumption either (per the original review, it ends stuck in the hook).",
        2: "BoskHook's @throws clause says bosk 'proceeds with the next hook', not 'defers the remaining hooks'. "
           "The claim that the wording now agrees with the deferral behavior is a misquote.",
        "added 0": "On interrupt, the remaining queued hooks are deferred 'to the next update' (Bosk.java:640); "
                   "they are not re-run immediately.",
        "added 1": "Bosk.java:648 logs 'Not draining the hook queue' to the main LOGGER, not HOOK_LOGGER.",
    },
    411: {
        1: "HookScannerTest.overriddenHookMethodWithoutAnnotation_stillFiresOnlyOnce asserts List.of(\"child\") "
           "(the virtual-dispatch handle fires the child's override), not List.of(\"parent\"); and it passes "
           "even without the fix, so it does not guard #399.",
        "added 0": "HookScanner collects into a LinkedHashMap (HookScanner.java:34), so registration order is "
                   "deterministic.",
        "added 1": "HookScanner rejects only static and private hook methods (HookScanner.java:44-48); "
                   "package-private methods are collected normally.",
    },
    413: {
        1: "PandoFormatDriver.java:202 throws IllegalStateException(\"Found parts without a main document\"), "
           "not InvalidCollectionContentsException; only the empty-collection case (line 207) and the "
           "revision-missing case (line ~181) throw InvalidCollectionContentsException.",
        "added 0": "The try-with-resources block closes the session on exit whether or not "
                   "commitTransactionIfAny throws; a failed commit does not leak the session.",
        "added 1": "MainDriver's outer catch (line 800) has an `if (e instanceof InterruptedException)` "
                   "branch that explicitly does NOT disconnect the driver; the claim that it disconnects on "
                   "interrupt is false.",
    },
    381: {
        0: "`FormatDriver<R> driverInUse = formatDriver;` is captured once, before the operationInSession "
           "lambda; it is not re-read per attempt. The claim that each retried attempt re-captures it is "
           "false.",
        "added 0": "onDisconnect (MainDriver.java:642) closes formatDriver unconditionally; there is no "
                   "guard for a replaced driver.",
        "added 1": "detectFormat (MainDriver.java:654) prefers Pando when the manifest has one, and falls "
                   "back to SEQUOIA; the claim that it prefers Sequoia when Pando is present is backwards.",
    },
}


def main():
    parser = argparse.ArgumentParser(description="Tamper a generated review for the judge-discrimination experiment.")
    parser.add_argument("pr", type=int)
    parser.add_argument("review", type=Path, help="the original review JSON")
    parser.add_argument("--out", required=True, type=Path, help="tampered review JSON")
    parser.add_argument("--record", required=True, type=Path, help="tampering record (markdown)")
    args = parser.parse_args()

    spec = TAMPERS.get(args.pr)
    if spec is None:
        sys.exit(f"no tamper spec for PR {args.pr}")

    review = json.loads(args.review.read_text())
    comments = review.get("comments", [])
    for idx, new_body in spec["replacements"].items():
        if idx >= len(comments):
            sys.exit(f"PR {args.pr}: comment {idx} out of range ({len(comments)} comments)")
        old_path_line = f"{comments[idx].get('path')}:{comments[idx].get('line')}"
        comments[idx]["body"] = new_body
        print(f"  replaced [{idx}] {old_path_line}")
    for i, add in enumerate(spec["additions"]):
        comments.append(add)
        print(f"  added    {add['path']}:{add['line']}")

    review["comments"] = comments
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(review, indent=2) + "\n")

    facts = CORRECT_FACTS[args.pr]
    lines = [f"# Tampering record, PR {args.pr}", "",
             "These are the wrong claims planted in the review, and the correct fact. "
             "The review was posted for a blind thumbs exercise.", ""]
    for idx, body in spec["replacements"].items():
        lines += [f"## Replaced comment {idx}", f"- **Wrong claim:** {body}", f"- **Correct fact:** {facts[idx]}", ""]
    for i, add in enumerate(spec["additions"]):
        lines += [f"## Added comment {i} ({add['path']}:{add['line']})",
                  f"- **Wrong claim:** {add['body']}", f"- **Correct fact:** {facts[f'added {i}']}", ""]
    args.record.parent.mkdir(parents=True, exist_ok=True)
    args.record.write_text("\n".join(lines) + "\n")
    print(f"wrote {args.out} and {args.record}")


if __name__ == "__main__":
    main()
