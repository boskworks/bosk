#!/usr/bin/env python3
"""Generate a fresh review of a PR's current state (no review rounds).

Used for the judge-discrimination experiment on PRs the review agent never
reviewed. Those PRs have no review submissions, so the round-based eval
machinery does not apply; their current-state packet (build-packet.py without
--round) is the review-time packet, because a merged PR's current state is its
reviewed state.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from eval import generate_review

sys.stdout.reconfigure(line_buffering=True)

DEFAULT_MODEL = "deepseek/deepseek-v4-flash"
DEFAULT_GEN_TIMEOUT = 1800


def main():
    parser = argparse.ArgumentParser(description="Generate a fresh review of a PR's current state.")
    parser.add_argument("pr", type=int)
    parser.add_argument("--packets", default=str(Path(__file__).resolve().parent.parent / "data" / "packets"))
    parser.add_argument("--repo-dir", default=".", help="the repository worktree")
    parser.add_argument("--prompt", default=str(Path(__file__).resolve().parent.parent / "review" / "reviewer.md"))
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--gen-timeout", type=int, default=DEFAULT_GEN_TIMEOUT)
    args = parser.parse_args()

    packet = Path(args.packets) / str(args.pr)
    snapshot = packet / "snapshot.json"
    worktree = packet / "worktree"
    if not snapshot.exists() or not worktree.exists():
        sys.exit(f"no current-state packet for PR {args.pr}; build it with build-packet.py {args.pr} first")
    generate_review(worktree, args.model, args.pr, snapshot, Path(args.prompt),
                    args.out, args.out.with_suffix(".log"), timeout=args.gen_timeout)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
