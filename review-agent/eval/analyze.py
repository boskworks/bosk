#!/usr/bin/env python3
"""Post hoc analysis of eval runs.

One tool for inspecting generation stats and logs, replacing the ad hoc scripts
improvised to diagnose run-to-run variance:

  stats <PR>    print a PR's generation stats (duration, tokens, cost, tool calls)
  compare <PR>  decompose the difference between a PR's current generation and the
                previous run's (from the archive) into more work vs slower model
  audit <PR>    list tool calls that left the review packet: git archaeology and
                reads of the eval cache, corpus, or reports
  monitor       how long each in-flight generation has been running, against the
                timeout

Artifacts live under data/eval/<key>/ (current) and data/eval/archive-<ts>/<key>/
(superseded); see PLAN.md.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = ROOT / "data" / "eval"

sys.stdout.reconfigure(line_buffering=True)


def newest_path(pr: str, suffix: str) -> Path | None:
    """The newest non-archived <PR><suffix> across run dirs."""
    best = None
    for candidate in EVAL_DIR.glob(f"*/{pr}{suffix}"):
        if "archive-" in candidate.parent.name:
            continue
        if best is None or candidate.stat().st_mtime > best.stat().st_mtime:
            best = candidate
    return best


def newest_archive(key: str, pr: str, suffix: str) -> Path | None:
    """The newest archived <PR><suffix> for the given key."""
    best = None
    for candidate in EVAL_DIR.glob(f"archive-*/{key}/{pr}{suffix}"):
        if best is None or candidate.parent.parent.name > best.parent.parent.name:
            best = candidate
    return best


def format_stats(stats: dict) -> str:
    tokens = stats.get("tokens") or {}
    cache = tokens.get("cache") or {}
    return (
        f"duration {stats.get('duration_seconds', 0) / 60:.1f} min | "
        f"tokens {tokens.get('total')} (in {tokens.get('input')}, out {tokens.get('output')}, "
        f"reasoning {tokens.get('reasoning')}, cache_read {cache.get('read')}) | "
        f"{stats.get('tokens_per_second')} tok/s | steps {stats.get('steps')} | "
        f"tools {stats.get('tool_calls')} | cost ${stats.get('cost')}"
    )


def cmd_stats(args):
    path = newest_path(str(args.pr), ".stats.json")
    if path is None:
        sys.exit(f"no stats file for PR {args.pr}; run the eval first")
    print(f"{path}:\n  {format_stats(json.loads(path.read_text()))}")


def cmd_compare(args):
    current = newest_path(str(args.pr), ".stats.json")
    if current is None:
        sys.exit(f"no stats file for PR {args.pr}; run the eval first")
    key = current.parent.name
    previous = newest_archive(key, str(args.pr), ".stats.json")
    new = json.loads(current.read_text())
    print(f"current  ({key}):\n  {format_stats(new)}")
    if previous is None:
        print("no archived previous generation to compare against")
        return
    old = json.loads(previous.read_text())
    print(f"previous ({previous.parent.parent.name}):\n  {format_stats(old)}")
    new_d, old_d = new["duration_seconds"], old["duration_seconds"]
    if not old_d:
        return
    print(f"\nratio: {new_d / old_d:.1f}x slower")
    new_t = (new.get("tokens") or {}).get("total", 0)
    old_t = (old.get("tokens") or {}).get("total", 0)
    if old_t:
        print(f"  work (tokens): {new_t / old_t:.1f}x")
    new_r, old_r = new.get("tokens_per_second"), old.get("tokens_per_second")
    if new_r and old_r:
        print(f"  model rate:   {new_r / old_r:.1f}x")


def outside_packet_calls(log: Path) -> list[str]:
    """Tool calls that left the review packet: git archaeology and reads outside it.

    Legitimate reads stay under data/packets/<PR>-r*/ (the worktree and snapshot)
    or name the reviewer prompt; the Attribution step's `git hash-object` is
    likewise allowed. Everything else is flagged: the agent should be a pure
    function of the packet.
    """
    flagged = []
    for line in log.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        part = ev.get("part") or {}
        if part.get("type") != "tool":
            continue
        inp = (part.get("state") or {}).get("input") or {}
        if part.get("tool") == "bash":
            cmd = inp.get("command") or ""
            if cmd.strip().startswith("git") and "hash-object" not in cmd:
                flagged.append("git archaeology: " + cmd.strip().splitlines()[0][:100])
            elif any(x in cmd for x in ("data/eval", "data/corpus", "reports/", "data/reviews")):
                flagged.append("outside packet: " + cmd.strip().splitlines()[0][:100])
        elif part.get("tool") == "read":
            fp = inp.get("filePath") or ""
            if "data/packets" not in fp and "reviewer.md" not in fp:
                flagged.append("read outside packet: " + fp.split("pr-review-assistant/")[-1][:100])
    return flagged


def cmd_audit(args):
    log = newest_path(str(args.pr), ".log")
    if log is None:
        sys.exit(f"no generation log for PR {args.pr}; run the eval first")
    calls = outside_packet_calls(log)
    print(f"PR {args.pr}: {len(calls)} tool call(s) that left the review packet")
    for call in calls:
        print("  " + call)


def cmd_monitor(args):
    now_ms = time.time() * 1000
    cutoff = now_ms - args.since * 60000
    found = False
    for run_dir in sorted(EVAL_DIR.iterdir()):
        if not run_dir.is_dir() or "archive-" in run_dir.name:
            continue
        for log in run_dir.glob("[0-9]*.log"):
            if log.with_suffix(".json").exists():
                continue
            if log.stat().st_mtime * 1000 < cutoff:
                continue
            ts = []
            for line in log.read_text().splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(ev, dict) and ev.get("timestamp") is not None:
                    ts.append(ev["timestamp"])
            if not ts:
                continue
            found = True
            print(f"PR {log.stem}: running {(now_ms - ts[0]) / 60000:.1f} min")
    if not found:
        print("no in-flight generations")


def main():
    parser = argparse.ArgumentParser(description="Post hoc analysis of eval runs.")
    sub = parser.add_subparsers(dest="command", required=True)
    for name, help_, func in (("stats", "print a PR's generation stats", cmd_stats),
                              ("compare", "decompose current vs previous generation time", cmd_compare),
                              ("audit", "list tool calls that left the review packet", cmd_audit),
                              ("monitor", "show in-flight generation durations", cmd_monitor)):
        p = sub.add_parser(name, help=help_)
        if name in ("stats", "compare", "audit"):
            p.add_argument("pr", type=int)
        if name == "monitor":
            p.add_argument("--since", type=int, default=30,
                           help="only show generations still being written within this many minutes")
        p.set_defaults(func=func)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
