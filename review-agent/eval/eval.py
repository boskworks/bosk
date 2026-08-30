#!/usr/bin/env python3
"""Offline evaluation of the review prompt.

Generates a review JSON per (PR, prompt) by running the review agent headlessly
via `opencode run` (no posting), then scores recall against that PR's gold
findings: the expert's own added review comments. Matching is semantic: the
model decides whether a review comment captures a gold finding, since string
similarity misses reworded matches. Virtual reviews and match results are run
artifacts, cached under data/eval/<prompt-hash>-<model>/<PR>.json (match results
alongside), keyed by prompt and model so prompt versions can be compared. They
are model outputs, not corpus data: regenerable, but not losslessly recreatable
from GitHub.

The review is generated against a *review packet* (see corpus/build-packet.py):
a snapshot of the PR's GitHub-visible information plus a fresh worktree at the
reviewed commit, filtered to the review round being evaluated. The generation
runs without GitHub credentials, so the agent cannot see anything beyond the
snapshot — in particular it cannot read the PR's review thread, which contains
the gold findings it is scored against. This matches the production review
procedure, where the reviewer is given the same packet inputs.

The matcher's verdicts are not trusted as a measurement until validated against
the expert's reactions; recall is reported meanwhile as an
unvalidated matcher reading. Precision is not measured here at all: a virtual
review has no expert reactions, so "would the expert dispute this comment?"
needs the calibrated judge, which is a later phase. See PLAN.md.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from match_findings import match_findings

sys.stdout.reconfigure(line_buffering=True)

DEFAULT_MODEL = "deepseek/deepseek-v4-flash"
DEFAULT_JOBS = 4
DEFAULT_GEN_TIMEOUT = 900
EVAL_ROUND = 1
_EMPTY_GH_DIR = tempfile.mkdtemp(prefix="review-gh-empty-")


def hash_prompt(prompt_path: Path) -> str:
    digest = subprocess.run(["git", "hash-object", str(prompt_path)], check=True,
                            capture_output=True, text=True).stdout.strip()
    return digest[:10]


def model_slug(model: str) -> str:
    return model.replace("/", "-").replace(".", "-")


def build_packet(pr: int, repo_dir: Path, packets_dir: Path, round_n: int = EVAL_ROUND) -> tuple[Path, Path]:
    """Build the review packet (worktree + snapshot) for (PR, round); returns their paths."""
    script = Path(__file__).resolve().parent.parent / "corpus" / "build-packet.py"
    subprocess.run([sys.executable, str(script), str(pr), "--round", str(round_n),
                    "--repo-dir", str(repo_dir), "--packets", str(packets_dir)],
                   check=True, capture_output=True, text=True)
    packet_dir = packets_dir / f"{pr}-r{round_n}"
    return packet_dir / "worktree", packet_dir / "snapshot.json"


def cleanup_packet(pr: int, repo_dir: Path, packets_dir: Path, round_n: int = EVAL_ROUND) -> None:
    script = Path(__file__).resolve().parent.parent / "corpus" / "build-packet.py"
    subprocess.run([sys.executable, str(script), str(pr), "--round", str(round_n),
                    "--repo-dir", str(repo_dir), "--packets", str(packets_dir), "--cleanup"],
                   capture_output=True, text=True)


def build_snapshot_only(pr: int, repo_dir: Path, packets_dir: Path, round_n: int = EVAL_ROUND) -> Path:
    """Build the packet's snapshot without the worktree; used for a cached review."""
    script = Path(__file__).resolve().parent.parent / "corpus" / "build-packet.py"
    subprocess.run([sys.executable, str(script), str(pr), "--round", str(round_n),
                    "--repo-dir", str(repo_dir), "--packets", str(packets_dir), "--snapshot-only"],
                   check=True, capture_output=True, text=True)
    return packets_dir / f"{pr}-r{round_n}" / "snapshot.json"


def cleanup_orphaned_packets(packets_dir: Path, repo_dir: Path) -> None:
    """Remove packet worktrees left behind by interrupted runs."""
    script = Path(__file__).resolve().parent.parent / "corpus" / "build-packet.py"
    for worktree in packets_dir.glob("*/worktree"):
        pr_label = worktree.parent.name
        pr = pr_label.split("-r")[0]
        subprocess.run([sys.executable, str(script), pr, "--round", "1",
                        "--repo-dir", str(repo_dir), "--packets", str(packets_dir), "--cleanup"],
                       capture_output=True, text=True)
        print(f"cleaned orphaned packet {pr_label}")


GENERATE_TEMPLATE = (
    "You are the review agent for PR {pr}. The repository under review is checked out "
    "in the current working directory, at the commit being reviewed. The PR's GitHub "
    "information — its title, description, the changed files, the review-time diffs, and "
    "any comments present when this review was requested — is in the snapshot at "
    "{snapshot}. The snapshot's diffs are the complete change set; do not reconstruct it "
    "from git history (no git log, merge-base, or ancestry exploration — examine the "
    "files directly in the working tree). gh is not available in this environment, so the "
    "snapshot is the only source of GitHub information; use it for the changed-file list "
    "and the diffs. Read the reviewer prompt at {prompt} and follow it exactly. Produce "
    "the review as the JSON document described in that prompt and write it to {out}. Do "
    "not post anything."
)


def instruction_hash() -> str:
    """Hash of the generation instruction template, so instruction changes invalidate the eval cache."""
    return hashlib.sha1(GENERATE_TEMPLATE.encode()).hexdigest()[:10]


def kill_generation(pid: int) -> None:
    """Kill the generation process and all its descendants.

    killpg only reaches the process group, and opencode can spawn a child in its
    own session that survives it and keeps running (and writing to the log). The
    descendant pids are collected before the kill, since a killed parent
    reparents its children and a parent-walk afterwards would miss them.
    """
    tree = [pid]
    frontier = [pid]
    while frontier:
        parent = frontier.pop()
        kids = subprocess.run(["pgrep", "-P", str(parent)], capture_output=True, text=True).stdout.split()
        for k in kids:
            if k.isdigit():
                tree.append(int(k))
                frontier.append(int(k))
    try:
        os.killpg(os.getpgid(pid), signal.SIGKILL)
    except ProcessLookupError:
        pass
    for p in tree:
        try:
            os.kill(p, signal.SIGKILL)
        except ProcessLookupError:
            pass


def generate_review(worktree: Path, model: str, pr: int, snapshot: Path, prompt: Path,
                    out: Path, log: Path, timeout: int = DEFAULT_GEN_TIMEOUT) -> None:
    instruction = GENERATE_TEMPLATE.format(pr=pr, snapshot=snapshot, prompt=prompt, out=out)
    env = dict(os.environ)
    env["GH_TOKEN"] = ""
    env["GITHUB_TOKEN"] = ""
    env["GH_CONFIG_DIR"] = _EMPTY_GH_DIR
    cmd = ["opencode", "run", "--auto", "--dir", str(worktree), "--model", model, "--format", "json", instruction]
    with log.open("w") as f:
        proc = subprocess.Popen(cmd, env=env, stdout=f, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            kill_generation(proc.pid)
            proc.wait()
            raise RuntimeError(f"generation timed out after {timeout}s (see {log})")
    if proc.returncode != 0:
        raise RuntimeError(f"generation failed (rc {proc.returncode}); see {log}")
    if not out.exists():
        raise RuntimeError(f"{out} was not written by the review agent (see {log})")


def archive_artifacts(eval_run_dir: Path, archive_dir: Path, pr: str) -> None:
    """Move a PR's current artifacts into the archive dir before regenerating it.

    The keyed run dir keeps only the current generation; superseded review, log,
    match results, and stats move to data/eval/archive-<ts>/<key>/ so successive
    runs of the same prompt stay comparable, and a timed-out generation's log is
    not lost when the PR is retried.
    """
    moved = []
    for name in (f"{pr}.json", f"{pr}.log", f"{pr}.match.json", f"{pr}.match.log", f"{pr}.stats.json"):
        src = eval_run_dir / name
        if src.exists():
            archive_dir.mkdir(parents=True, exist_ok=True)
            src.rename(archive_dir / name)
            moved.append(name)
    if moved:
        print(f"PR {pr}: archived {', '.join(moved)} to {archive_dir}")


def extract_stats(log: Path, snapshot: dict, snapshot_bytes: int | None) -> dict:
    """Diagnostics for one generation, from its opencode log and the snapshot.

    The log carries per-step timestamps, cumulative token usage (including
    reasoning and cache reads), per-step cost, and assistant-turn latency
    windows; the snapshot's size and diff volume let input size correlate with
    generation time.
    """
    ts = []
    step_finishes = []
    text_times = []
    tool_calls = {}
    cost = 0.0
    if log.exists():
        for line in log.read_text().splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(ev, dict) or ev.get("timestamp") is None:
                continue
            part = ev.get("part") or {}
            ts.append(ev["timestamp"])
            typ = ev.get("type")
            if typ == "step_finish":
                step_finishes.append(part)
                c = part.get("cost")
                if isinstance(c, (int, float)):
                    cost += c
            elif typ == "text" and isinstance(part.get("time"), dict):
                text_times.append(part["time"])
            elif typ == "tool_use" and part.get("tool"):
                tool_calls[part["tool"]] = tool_calls.get(part["tool"], 0) + 1
    tokens = {}
    for sf in reversed(step_finishes):
        if sf.get("tokens"):
            tokens = sf["tokens"]
            break
    duration = (ts[-1] - ts[0]) / 1000.0 if len(ts) > 1 else 0.0
    diffs = snapshot.get("diffs", [])
    return {
        "duration_seconds": round(duration, 1),
        "steps": len(step_finishes),
        "tool_calls": tool_calls,
        "tokens": tokens,
        "cost": round(cost, 6),
        "tokens_per_second": round(tokens.get("total", 0) / duration, 1) if duration else None,
        "assistant_turn_seconds": [round((w["end"] - w["start"]) / 1000.0, 2) for w in text_times],
        "snapshot_bytes": snapshot_bytes,
        "files": len(snapshot.get("files", [])),
        "diff_lines": sum(d.get("diff", "").count("\n") for d in diffs),
    }


def ensure_stats(eval_run_dir: Path, pr: str, snapshot: dict, snapshot_bytes: int | None) -> Path:
    """Write a PR's stats file from its generation log; backfills reused artifacts."""
    stats_path = eval_run_dir / f"{pr}.stats.json"
    if not stats_path.exists():
        stats = extract_stats(eval_run_dir / f"{pr}.log", snapshot, snapshot_bytes)
        stats_path.write_text(json.dumps(stats, indent=2) + "\n")
    return stats_path


def generation_slowdown_note(archive_dir: Path, eval_run_dir: Path, pr: str,
                             factor: float = 2.0) -> str | None:
    """A note if this generation took more than `factor`x longer than the previous one.

    The previous generation's stats sit in the archive dir, moved there before
    regeneration, so the comparison is between successive runs of the same PR and
    prompt. A large slowdown is worth investigating — the model may be degraded or
    rate-limited — rather than absorbed silently.
    """
    old_path = archive_dir / f"{pr}.stats.json"
    new_path = eval_run_dir / f"{pr}.stats.json"
    if not (old_path.exists() and new_path.exists()):
        return None
    old = json.loads(old_path.read_text()).get("duration_seconds")
    new = json.loads(new_path.read_text()).get("duration_seconds")
    if old and new and new > factor * old:
        return f"generation took {new:.0f}s vs {old:.0f}s previously ({new / old:.1f}x) — investigate"
    return None


def evaluate_one(pr_dir: Path, eval_run_dir: Path, archive_dir: Path, repo_dir: Path,
                 model: str, prompt_hash: str, prompt: Path, refresh: bool,
                 gen_timeout: int, snapshot: Path | None, worktree: Path | None) -> tuple:
    """Generate, match and score one PR's virtual review. Returns (name, recalled, total, report_lines, note).

    The packet (snapshot and, for regeneration, the worktree) is built upfront by
    the caller, so evaluate_one does no git worktree operations of its own.
    Recall is measured against the gold findings whose file is present in the
    review-time snapshot (the reachability guard); findings on files the reviewer
    could not have seen are excluded from the denominator.
    """
    n = pr_dir.name
    try:
        record = json.loads((pr_dir / "classification.json").read_text())
        all_gold = record.get("added_findings", [])
        if not all_gold:
            return n, 0, 0, [], "skip (no gold findings)"
        if snapshot is None:
            return n, 0, 0, [f"## PR {n}", "_packet build failed_", ""], "packet build failed"
        snapshot_data = json.loads(snapshot.read_text())
        snapshot_bytes = snapshot.stat().st_size
        reachable_files = set(snapshot_data.get("files", []))
        if reachable_files:
            gold = [f for f in all_gold if f["path"] in reachable_files]
            excluded = [f for f in all_gold if f["path"] not in reachable_files]
        else:
            gold = all_gold
            excluded = []
        if not gold:
            return n, 0, 0, [f"## PR {n}", "_no reachable findings_", ""], None
        out = eval_run_dir / f"{n}.json"
        match_out = eval_run_dir / f"{n}.match.json"
        if worktree is None:
            print(f"PR {n}: reuse cached {out}")
        else:
            print(f"PR {n}: generating — no cached review for prompt {prompt_hash} (this can take minutes)")
            archive_artifacts(eval_run_dir, archive_dir, n)
            generate_review(worktree, model, int(n), snapshot, prompt, out,
                            eval_run_dir / f"{n}.log", timeout=gen_timeout)
        ensure_stats(eval_run_dir, n, snapshot_data, snapshot_bytes)
        slowdown = generation_slowdown_note(archive_dir, eval_run_dir, n)
        if slowdown:
            print(f"PR {n}: WARNING: {slowdown}", file=sys.stderr)
        review = json.loads(out.read_text())
        comments = review.get("comments", [])
        if match_out.exists() and not refresh:
            matched = json.loads(match_out.read_text()).get("matched", [])
        else:
            matched = match_findings(repo_dir, model, [f["body"] for f in gold], comments,
                                     eval_run_dir / f"{n}.match.log")
            match_out.write_text(json.dumps({"matched": matched}))
        matched_indices = {m["finding"] for m in matched}
        recalled = len(matched_indices)
        total = len(gold)
        missed = [f for i, f in enumerate(gold) if i not in matched_indices]
        lines = [f"## PR {n}", f"_recall {recalled}/{total} ({recalled / total:.0%})_"]
        if slowdown:
            lines.append(f"- WARNING: {slowdown}")
        for f in excluded:
            lines.append(f"- EXCLUDED (file not in review-time context): {f['body']}")
        lines += [f"- MISSED: {f['body']}" for f in missed]
        for m in sorted(matched, key=lambda x: x["finding"]):
            g = gold[m["finding"]]
            c = comments[m["comment"]]
            lines.append(f"- CAPTURED: {g['body']}")
            lines.append(f"    <- comment {m['comment']}: {c['body']}")
        lines.append("")
        return n, recalled, total, lines, None
    except Exception as e:
        print(f"PR {n}: generation failed: {e}", file=sys.stderr)
        return n, 0, 0, [f"## PR {n}: FAILED ({e})", ""], f"failed: {e}"


def main():
    parser = argparse.ArgumentParser(description="Offline eval of the review prompt (recall vs gold findings).")
    parser.add_argument("--corpus", default=str(Path(__file__).resolve().parent.parent / "data" / "corpus"))
    parser.add_argument("--eval-dir", default=str(Path(__file__).resolve().parent.parent / "data" / "eval"),
                        help="where to cache virtual reviews and match results (run artifacts)")
    parser.add_argument("--packets", default=str(Path(__file__).resolve().parent.parent / "data" / "packets"),
                        help="where to build review packets (worktrees and snapshots)")
    parser.add_argument("--repo-dir", default=".", help="the repository worktree the review agent runs in")
    parser.add_argument("--prompt", default=str(Path(__file__).resolve().parent.parent / "review" / "reviewer.md"))
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--prs", help="comma-separated PR numbers to evaluate (default: all classified)")
    parser.add_argument("--jobs", type=int, default=DEFAULT_JOBS,
                        help="how many review generations to run in parallel (default %(default)s)")
    parser.add_argument("--refresh", action="store_true", help="regenerate existing virtual reviews")
    parser.add_argument("--gen-timeout", type=int, default=DEFAULT_GEN_TIMEOUT,
                        help="seconds to allow each review generation (default %(default)s)")
    parser.add_argument("--context-only", action="store_true",
                        help="only build the review packets, then exit (no generation)")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "data" / "reports" / "eval.md"))
    args = parser.parse_args()

    corpus = Path(args.corpus)
    eval_dir = Path(args.eval_dir)
    packets_dir = Path(args.packets)
    repo_dir = Path(args.repo_dir)
    prompt = Path(args.prompt)
    prompt_hash = hash_prompt(prompt)
    base = f"{prompt_hash}-{model_slug(args.model)}-{instruction_hash()}"
    eval_run_dir = eval_dir / base
    eval_run_dir.mkdir(parents=True, exist_ok=True)
    archive_dir = eval_dir / f"archive-{time.strftime('%Y%m%d-%H%M%S')}" / base

    pr_dirs = sorted(p for p in corpus.iterdir() if p.name.isdigit() and (p / "classification.json").exists())
    if args.prs:
        wanted = {int(n) for n in args.prs.split(",")}
        pr_dirs = [p for p in pr_dirs if int(p.name) in wanted]

    if args.context_only:
        for pr_dir in pr_dirs:
            worktree, snapshot = build_packet(int(pr_dir.name), repo_dir, packets_dir)
            print(f"PR {pr_dir.name}: wrote {snapshot}")
        return

    cleanup_orphaned_packets(packets_dir, repo_dir)

    # Build the packets upfront, sequentially, so git worktree operations never
    # run concurrently (parallel adds race on git's worktree lock). Generation-
    # needed PRs get a full packet (snapshot + worktree); cached PRs get just the
    # snapshot, which the reachability guard still needs.
    built = {}
    for pr_dir in pr_dirs:
        n = int(pr_dir.name)
        gen = args.refresh or not (eval_run_dir / f"{n}.json").exists()
        try:
            if gen:
                worktree, snapshot = build_packet(n, repo_dir, packets_dir)
            else:
                snapshot = build_snapshot_only(n, repo_dir, packets_dir)
                worktree = None
        except Exception as e:
            print(f"PR {n}: packet build failed: {e}", file=sys.stderr)
            built[n] = (None, None)
            continue
        built[n] = (snapshot, worktree)

    results = []
    try:
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(evaluate_one, pr_dir, eval_run_dir, archive_dir, repo_dir,
                                   args.model, prompt_hash, prompt, args.refresh, args.gen_timeout,
                                   built[int(pr_dir.name)][0], built[int(pr_dir.name)][1])
                       for pr_dir in pr_dirs]
            for fut in as_completed(futures):
                results.append(fut.result())
    finally:
        for n, (snapshot, worktree) in built.items():
            if worktree is not None:
                try:
                    cleanup_packet(n, repo_dir, packets_dir)
                except Exception as e:
                    print(f"PR {n}: packet cleanup failed: {e}", file=sys.stderr)

    results.sort(key=lambda r: int(r[0]))
    lines = [f"# Offline eval — prompt {prompt_hash} ({args.model}, {args.jobs} jobs)",
             f"_Run: {eval_run_dir}_",
             f"_Generated {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}_", ""]
    total_recalled = total_gold = succeeded = 0
    for n, recalled, total, pr_lines, note in results:
        if note:
            print(f"PR {n}: {note}")
            continue
        succeeded += 1
        print(f"PR {n}: recall {recalled}/{total}")
        total_recalled += recalled
        total_gold += total
        lines += pr_lines

    overall = f"{total_recalled}/{total_gold} ({total_recalled / total_gold:.0%})" if total_gold else "n/a"
    lines.append(f"## Overall recall: {overall} ({succeeded}/{len(results)} PRs evaluated)")
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines) + "\n")
    print(f"overall recall {overall}; wrote {out_path}")


if __name__ == "__main__":
    main()
