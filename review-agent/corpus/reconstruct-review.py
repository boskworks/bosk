#!/usr/bin/env python3
"""Reconstruct a posted review's JSON from the corpus PR data.

post-review.py archives each review it posts to data/reviews/<PR>.json. Reviews
posted before that feature existed, or whose archive was lost with a deleted
data/ cache, are reconstructed from the corpus PR data: the top-level
"[review]"-marked comments (stripped of the marker), plus the review summary and
verdict from reviews.json. The result is what measure-agreement.py and
validate-matcher.py consume. The prompt hash is not recoverable from a posted
review, so it is left null.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

ROOT = Path(__file__).resolve().parent.parent
REVIEWER = "prdoyle-agent"
MARK = "[review]"
VERDICTS = {"APPROVED": "APPROVE", "CHANGES_REQUESTED": "REQUEST_CHANGES",
            "COMMENTED": "COMMENT", "PENDING": "COMMENT"}


def reconstruct(pr: int, corpus: Path, reviews: Path) -> Path:
    pr_dir = corpus / str(pr)
    comments = json.loads((pr_dir / "comments.json").read_text())
    rvs = json.loads((pr_dir / "reviews.json").read_text())
    summary = next((r["body"] for r in rvs if r.get("user", {}).get("login") == REVIEWER and r.get("body")), "")
    state = next((r["state"] for r in rvs if r.get("user", {}).get("login") == REVIEWER and r.get("body")), "COMMENTED")
    review_comments = [
        {"path": c.get("path"), "line": c.get("line"),
         "body": c["body"][len(MARK):].strip()}
        for c in comments
        if c.get("user", {}).get("login") == REVIEWER
        and c.get("in_reply_to_id") is None
        and c.get("body", "").startswith(MARK)
    ]
    out = {"summary": summary, "verdict": VERDICTS.get(state, "COMMENT"), "prompt": None,
           "comments": review_comments}
    reviews.mkdir(parents=True, exist_ok=True)
    path = reviews / f"{pr}.json"
    path.write_text(json.dumps(out, indent=2) + "\n")
    return path


def main():
    parser = argparse.ArgumentParser(description="Reconstruct a posted review JSON from the corpus.")
    parser.add_argument("pr", type=int)
    parser.add_argument("--corpus", default=str(ROOT / "data" / "corpus"))
    parser.add_argument("--reviews", default=str(ROOT / "data" / "reviews"))
    args = parser.parse_args()
    path = reconstruct(args.pr, Path(args.corpus), Path(args.reviews))
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
