#!/usr/bin/env python3
"""Post a responses JSON as replies to, and resolutions of, PR review threads.

The response cycle's output is a JSON document (see the pr-reviewer skill): one
entry per thread, carrying the thread's root comment id, the reply text, and
whether to resolve the thread. This script is the only path from that JSON to
GitHub: it posts each reply and resolves each accepted thread. It is the
response cycle's counterpart to post-review.py.

Both the reviewer and the PR author post under the same account
(`prdoyle-agent`), so replies are stamped with the `[review]` marker, mirroring
post-review.py; author comments carry no marker. The marker is applied here,
not in the responses JSON, so it cannot be forgotten.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

MARK = "[review]"


def gh(*args: str) -> str:
    result = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"gh failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout


def load_responses(path: Path) -> list:
    doc = json.loads(path.read_text())
    entries = doc.get("responses")
    if not isinstance(entries, list):
        raise ValueError("'responses' must be a list")
    for e in entries:
        if not isinstance(e.get("comment_id"), int):
            raise ValueError(f"each response needs an integer 'comment_id'; got {e!r}")
        if not isinstance(e.get("reply"), str):
            raise ValueError(f"each response needs a string 'reply'; got {e!r}")
        if not isinstance(e.get("resolve"), bool):
            raise ValueError(f"each response needs a boolean 'resolve'; got {e!r}")
    return entries


def review_threads(repo: str, pr: int) -> list:
    """The PR's review threads, each mapped from its comments' database ids."""
    owner, name = repo.split("/", 1)
    query = (
        f'query {{ repository(owner: "{owner}", name: "{name}") {{ '
        f'pullRequest(number: {pr}) {{ reviewThreads(first: 100) {{ nodes {{ '
        f'id isResolved comments(first: 100) {{ nodes {{ databaseId }} }} }} }} }} }} }}'
    )
    nodes = json.loads(gh("graphql", "-f", f"query={query}"))
    return nodes["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"]


def reply_to(repo: str, pr: int, comment_id: int, body: str, mark: bool) -> None:
    body = f"{MARK} {body}" if mark else body
    gh(f"repos/{repo}/pulls/{pr}/comments/{comment_id}/replies", "--method", "POST",
       "-f", f"body={body}", "--jq", ".id")


def resolve_thread(thread_id: str) -> None:
    gh("graphql",
       "-f", f'query=mutation {{ resolveReviewThread(input: {{threadId: "{thread_id}"}}) {{ thread {{ isResolved }} }} }}',
       "--jq", ".data.resolveReviewThread.thread.isResolved")


def main():
    parser = argparse.ArgumentParser(
        description="Post a responses JSON: reply to each review thread and resolve the accepted ones.")
    parser.add_argument("pr", type=int, help="pull request number")
    parser.add_argument("responses", type=Path, help="path to the responses JSON")
    parser.add_argument("--repo", default="boskworks/bosk")
    parser.add_argument("--no-mark", action="store_true",
                        help=f"don't prepend '{MARK}' to posted replies")
    args = parser.parse_args()

    try:
        entries = load_responses(args.responses)
    except (ValueError, json.JSONDecodeError) as e:
        print(f"invalid responses JSON: {e}", file=sys.stderr)
        return 1

    try:
        nodes = review_threads(args.repo, args.pr)
    except (RuntimeError, KeyError, IndexError) as e:
        print(f"could not read the PR's review threads: {e}", file=sys.stderr)
        return 1
    thread_by_comment = {}
    for t in nodes:
        for c in t.get("comments", {}).get("nodes", []):
            if c.get("databaseId") is not None:
                thread_by_comment[c["databaseId"]] = t

    for e in entries:
        thread = thread_by_comment.get(e["comment_id"])
        if thread is None:
            print(f"WARN no review thread contains comment {e['comment_id']}; skipping", file=sys.stderr)
            continue
        if e["reply"]:
            reply_to(args.repo, args.pr, e["comment_id"], e["reply"], not args.no_mark)
            print(f"replied on comment {e['comment_id']}")
        if e["resolve"] and not thread.get("isResolved"):
            resolve_thread(thread["id"])
            print(f"resolved thread {thread['id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
