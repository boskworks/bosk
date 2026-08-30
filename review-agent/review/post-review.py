#!/usr/bin/env python3
"""Post a structured review JSON as a GitHub pull-request review.

The review agent's output is a JSON document (see reviewer.md). This script
is the only path from that JSON to GitHub: it submits the review in one call
carrying the summary body, the verdict, and the inline comments. If a comment's
line anchor is rejected, it reports the offending comment; --per-comment then
posts each comment individually so one bad anchor does not lose the rest.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

VERDICTS = {"APPROVE", "REQUEST_CHANGES", "COMMENT"}
MARK = "[review]"


def gh(repo: str, *args: str) -> str:
    cmd = ["gh", "api", *args]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"gh failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout


def load_review(path: Path) -> dict:
    review = json.loads(path.read_text())
    if not isinstance(review.get("summary"), str):
        raise ValueError("review JSON must have a string 'summary' field")
    verdict = (review.get("verdict") or "COMMENT").upper()
    if verdict not in VERDICTS:
        raise ValueError(f"verdict must be one of {sorted(VERDICTS)}; got {review.get('verdict')!r}")
    comments = review.get("comments", [])
    if not isinstance(comments, list):
        raise ValueError("'comments' must be a list")
    for c in comments:
        if not isinstance(c.get("path"), str) or not isinstance(c.get("body"), str):
            raise ValueError(f"each comment needs 'path' and 'body'; got {c!r}")
        if c.get("subject_type") != "file" and not isinstance(c.get("line"), int):
            raise ValueError(f"each comment needs an integer 'line' (or subject_type 'file'); got {c!r}")
    return {"summary": review["summary"], "verdict": verdict, "prompt": review.get("prompt"),
            "comments": comments}


def comment_payload(c: dict, mark: bool) -> dict:
    body = f"{MARK} {c['body']}" if mark else c["body"]
    if c.get("subject_type") == "file":
        return {"path": c["path"], "subject_type": "file", "body": body}
    return {"path": c["path"], "line": c["line"], "side": "RIGHT", "body": body}


def post_review(repo: str, pr: int, review: dict, mark: bool) -> None:
    payload = {
        "body": review["summary"],
        "event": review["verdict"],
        "comments": [comment_payload(c, mark) for c in review["comments"]],
    }
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(payload, f)
        tmp = Path(f.name)
    try:
        gh(repo, f"repos/{repo}/pulls/{pr}/reviews", "--method", "POST", "--input", str(tmp))
    finally:
        tmp.unlink(missing_ok=True)


def post_per_comment(repo: str, pr: int, review: dict, mark: bool) -> None:
    head_sha = gh(repo, f"repos/{repo}/pulls/{pr}", "--jq", ".head.sha").strip()
    posted = 0
    for c in review["comments"]:
        payload = comment_payload(c, mark)
        payload["commit_id"] = head_sha
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(payload, f)
            tmp = Path(f.name)
        try:
            gh(repo, f"repos/{repo}/pulls/{pr}/comments", "--method", "POST", "--input", str(tmp))
            posted += 1
        except RuntimeError as e:
            print(f"WARN skipping unpostable comment {c.get('path')}:{c.get('line')}: {e}", file=sys.stderr)
        finally:
            tmp.unlink(missing_ok=True)
    print(f"posted {posted}/{len(review['comments'])} comments")
    post_review(repo, pr, {"summary": review["summary"], "verdict": review["verdict"], "comments": []}, mark)


def main():
    parser = argparse.ArgumentParser(description="Post a review JSON as a GitHub pull-request review.")
    parser.add_argument("pr", type=int, help="pull request number")
    parser.add_argument("review", type=Path, help="path to the review JSON")
    parser.add_argument("--repo", default="boskworks/bosk")
    parser.add_argument("--event", help="override the review's verdict")
    parser.add_argument("--per-comment", action="store_true",
                        help="post each comment individually, skipping ones with bad line anchors")
    parser.add_argument("--no-mark", action="store_true",
                        help=f"don't prepend '{MARK}' to posted comments")
    args = parser.parse_args()

    try:
        review = load_review(args.review)
    except (ValueError, json.JSONDecodeError) as e:
        print(f"invalid review JSON: {e}", file=sys.stderr)
        return 1
    if args.event:
        review["verdict"] = args.event.upper()
    try:
        if args.per_comment:
            post_per_comment(args.repo, args.pr, review, not args.no_mark)
        else:
            post_review(args.repo, args.pr, review, not args.no_mark)
    except RuntimeError as e:
        print(f"posting failed: {e}", file=sys.stderr)
        print("hint: a rejected line anchor is the usual cause; retry with --per-comment", file=sys.stderr)
        return 1
    reviews_dir = Path(__file__).resolve().parent.parent / "data" / "reviews"
    reviews_dir.mkdir(parents=True, exist_ok=True)
    (reviews_dir / f"{args.pr}.json").write_text(json.dumps(review, indent=2) + "\n")
    print(f"posted review {args.pr}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
