#!/usr/bin/env python3
"""Build a review packet for a PR: the reviewer's inputs.

A review packet is the pair of inputs a review is produced from, in both the
day-to-day Review workflow and the eval: (1) a snapshot of the GitHub-visible
information — the PR title and description, base and head, the changed files,
the review-time diffs, and the comments and reactions present at the
review-request moment — and (2) a fresh worktree at the commit being reviewed.

The snapshot is filtered by review round: round 1 is a fresh review, so it
contains no prior comments; round N is a follow-up, so it contains the
conversation up to round N (previous review comments, the maintainer's reactions
and replies). Without --round, the snapshot is the current state (production).
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

ROOT = Path(__file__).resolve().parent.parent
REVIEWER = "prdoyle-agent"

# The comment fields copied unchanged into the snapshot, under their GitHub
# names; only reactions (fetched per comment) is merged in when the snapshot is
# built.
SNAPSHOT_COMMENT_FIELDS = ("id", "user", "path", "line", "original_line", "start_line",
                           "original_start_line", "side", "original_side",
                           "original_commit_id", "diff_hunk", "body",
                           "in_reply_to_id", "created_at")


def git(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True, cwd=cwd).stdout


def reviewer_submissions(reviews_path: Path) -> list:
    """The reviewer's review submissions in chronological order (round boundaries)."""
    reviews = json.loads(reviews_path.read_text())
    return sorted(
        (r for r in reviews if r.get("user", {}).get("login") == REVIEWER and r.get("submitted_at")),
        key=lambda r: r["submitted_at"],
    )


def round_boundary(pr_dir: Path, round_n: int | None) -> str | None:
    """The submitted_at of round N's review submission, or None for the current state."""
    if round_n is None:
        return None
    subs = reviewer_submissions(pr_dir / "reviews.json")
    if round_n > len(subs):
        sys.exit(f"PR {pr_dir.name} has {len(subs)} reviewer submissions; round {round_n} requested")
    return subs[round_n - 1]["submitted_at"]


def round_comments(comments: list, boundary: str | None) -> list:
    if boundary is None:
        return comments
    return [c for c in comments if c.get("created_at", "") >= boundary]


def build_diffs(pr_dir: Path, repo_dir: Path, pr: dict, boundary: str | None) -> list:
    """Per-anchor review-time diffs for the round; the full change set for the current state."""
    comments = json.loads((pr_dir / "comments.json").read_text())
    anchors = sorted({c["original_commit_id"] for c in round_comments(comments, boundary)
                      if c.get("original_commit_id")})
    if pr.get("merged") and anchors:
        base_sha = pr["base"]["sha"]
        parts = []
        for anchor in anchors:
            fork = git("-C", str(repo_dir), "merge-base", base_sha, anchor).strip()
            diff = git("-C", str(repo_dir), "diff", fork, anchor)
            parts.append({"anchor": anchor, "diff": diff})
        if parts:
            return parts
    # The reviewed change set is the branch's own commits up to the commit being
    # reviewed, computed locally. Diffing against the *reviewed head* — the
    # round's anchor, or the PR head for the current state — keeps the snapshot
    # consistent with the worktree and omits commits that postdate the review: a
    # review must not see the future. The corpus's commits are always in the
    # local clone, so git is the only source of truth here; a failure is a real
    # error, not a signal to consult a different source.
    reviewed = review_head(pr, comments, boundary)
    fork = git("-C", str(repo_dir), "merge-base", pr["base"]["sha"], reviewed).strip()
    diff = git("-C", str(repo_dir), "diff", fork, reviewed)
    return [{"anchor": None, "diff": diff}]


def snapshot_files(diffs: list) -> list:
    files = []
    for d in diffs:
        for line in d["diff"].splitlines():
            if line.startswith("diff --git "):
                f = line.split(" b/", 1)[-1]
                if f not in files:
                    files.append(f)
    return files


def build_snapshot(pr_dir: Path, repo_dir: Path, pr: dict, boundary: str | None) -> dict:
    comments = json.loads((pr_dir / "comments.json").read_text())
    diffs = build_diffs(pr_dir, repo_dir, pr, boundary)
    present = comments if boundary is None else [c for c in comments
                                                 if c.get("created_at", "") < boundary]
    reactions = {}
    for c in present:
        rfile = pr_dir / "reactions" / f"{c.get('id')}.json"
        if rfile.exists():
            reactions[c["id"]] = json.loads(rfile.read_text())
    return {
        "number": pr.get("number"),
        "title": pr.get("title"),
        "description": pr.get("body"),
        "base": pr.get("base"),
        "head": pr.get("head"),
        "review_head": review_head(pr, comments, boundary),
        "files": snapshot_files(diffs),
        "diffs": diffs,
        "comments": [
            {f: c.get(f) for f in SNAPSHOT_COMMENT_FIELDS}
            | {"reactions": reactions.get(c.get("id"))}
            for c in present
        ],
    }


def review_head(pr: dict, comments: list, boundary: str | None) -> str:
    """The commit being reviewed: the round's anchor, or the PR head for the current state."""
    if boundary is None:
        return pr["head"]["sha"]
    anchors = [c["original_commit_id"] for c in round_comments(comments, boundary)
               if c.get("original_commit_id")]
    return max(anchors) if anchors else pr["head"]["sha"]


def main():
    parser = argparse.ArgumentParser(description="Build a review packet (snapshot + worktree) for a PR.")
    parser.add_argument("pr", type=int)
    parser.add_argument("--round", type=int,
                        help="the review round being evaluated (1 = fresh; default: current state)")
    parser.add_argument("--corpus", default=str(ROOT / "data" / "corpus"))
    parser.add_argument("--repo-dir", default=".", help="the repository worktree (for git)")
    parser.add_argument("--packets", default=str(ROOT / "data" / "packets"))
    parser.add_argument("--cleanup", action="store_true", help="remove the packet's worktree instead of building")
    parser.add_argument("--snapshot-only", action="store_true",
                        help="build the snapshot without the worktree (e.g. for a cached review)")
    args = parser.parse_args()

    pr_dir = Path(args.corpus) / str(args.pr)
    pr = json.loads((pr_dir / "pr.json").read_text())
    label = str(args.pr) if args.round is None else f"{args.pr}-r{args.round}"
    packet_dir = Path(args.packets) / label

    if args.cleanup:
        worktree = packet_dir / "worktree"
        if worktree.exists():
            git("worktree", "remove", "--force", str(worktree), cwd=Path(args.repo_dir))
        shutil.rmtree(packet_dir, ignore_errors=True)
        print(f"removed {packet_dir}")
        return

    boundary = round_boundary(pr_dir, args.round)
    snapshot = build_snapshot(pr_dir, Path(args.repo_dir), pr, boundary)
    packet_dir.mkdir(parents=True, exist_ok=True)
    (packet_dir / "snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n")
    print(f"wrote {packet_dir / 'snapshot.json'}")

    if args.snapshot_only:
        return

    worktree = packet_dir / "worktree"
    if not worktree.exists():
        git("worktree", "add", "--detach", str(worktree), snapshot["review_head"],
            cwd=Path(args.repo_dir))
    print(f"worktree {worktree} at {snapshot['review_head']}")


if __name__ == "__main__":
    main()
