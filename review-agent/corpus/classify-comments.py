#!/usr/bin/env python3
"""Classify review comments into ground-truth labels.

Reads corpus/<PR>/comments.json plus per-comment reactions and attributes each
review comment with the principal expert's label, following the priority
in PLAN.md:

  1. emoji          -- a thumb reaction by the expert is authoritative
  2. "Question:"    -- an expert reply prefixed with "Question:" overrides
  3. unjudged       -- otherwise omitted from metrics

The reviewer and the PR author both post under the reviewer account, so the
account alone cannot tell them apart; review comments are the top-level ones
stamped with the "[review]" marker that post-review.py prepends. The expert's
own top-level review comments (not replies) are *added findings*: problems the
automated review missed that the maintainer had to point out. They are the
recall signal — how much of what the maintainer found the review caught. GitHub
is the system of record: labels are a pure function of GitHub state, recomputed
fresh each run. Label valence beyond the explicit emoji and "Question:" signals
is not guessed here; it is the judge's job.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

EXPERT = "prdoyle"
REVIEWER = "prdoyle-agent"
REVIEW_MARK = "[review]"


def reaction_label(expert_reactions):
    """Return ('disputed'|'confirmed', source) if the expert reacted, else None."""
    for reaction in expert_reactions:
        if reaction.get("user", {}).get("login") != EXPERT:
            continue
        content = reaction.get("content")
        if content == "-1":
            return ("disputed", "emoji")
        if content == "+1":
            return ("confirmed", "emoji")
    return None


def classify_pr(pr_dir: Path):
    comments = json.loads((pr_dir / "comments.json").read_text())

    # Reactions by comment id.
    reactions = {}
    for path in (pr_dir / "reactions").glob("*.json"):
        comment_id = int(path.stem)
        reactions[comment_id] = json.loads(path.read_text())

    review_comments = []   # the review agent's comments, with expert labels
    added_findings = []    # the expert's own top-level review comments
    excluded = []          # everything else (e.g. replies; their text still informs labels)

    for c in comments:
        body = c.get("body", "")
        is_reply = c.get("in_reply_to_id") is not None
        user = c.get("user", {}).get("login")
        # The reviewer and the PR author both post under the reviewer account,
        # so the account alone cannot tell them apart. Review comments are the
        # top-level ones post-review.py stamped with the [review] marker; author
        # comments carry no marker and fall through to excluded.
        if not is_reply and user == REVIEWER and body.strip().startswith(REVIEW_MARK):
            # A review comment: strip the marker, label it by the expert's engagement.
            clean_body = body.strip()[len(REVIEW_MARK):].lstrip()
            replies = [cc for cc in comments if cc.get("in_reply_to_id") == c["id"] and cc.get("user", {}).get("login") == EXPERT]
            reply_texts = [cc.get("body", "") for cc in replies]
            label, source = "unjudged", "none"
            rl = reaction_label(reactions.get(c["id"], []))
            if rl:
                label, source = rl
            elif any(t.startswith("Question:") for t in reply_texts):
                label, source = "question", "prefix"
            review_comments.append({
                "comment_id": c["id"],
                "body": clean_body,
                "path": c.get("path"),
                "html_url": c.get("html_url"),
                "label": label,
                "source": source,
                "replies": reply_texts,
            })
        elif not is_reply and user == EXPERT:
            added_findings.append({
                "comment_id": c["id"],
                "body": body,
                "path": c.get("path"),
                "html_url": c.get("html_url"),
            })
        else:
            excluded.append({
                "comment_id": c["id"],
                "body": body,
                "user": user,
                "in_reply_to_id": c.get("in_reply_to_id"),
            })

    reviews = []
    reviews_path = pr_dir / "reviews.json"
    if reviews_path.exists():
        for rv in json.loads(reviews_path.read_text()):
            if rv.get("user", {}).get("login") == REVIEWER and rv.get("body"):
                reviews.append({"state": rv.get("state"), "body": rv["body"]})

    pr_meta = json.loads((pr_dir / "pr.json").read_text())
    return {
        "number": pr_meta.get("number"),
        "title": pr_meta.get("title"),
        "review_comments": review_comments,
        "added_findings": added_findings,
        "excluded": excluded,
        "reviews": reviews,
    }


def main():
    parser = argparse.ArgumentParser(description="Classify corpus PRs into ground-truth labels.")
    parser.add_argument("--corpus", default=str(Path(__file__).resolve().parent.parent / "data" / "corpus"),
                        help="corpus directory containing corpus/<PR>/ records")
    parser.add_argument("--pr", type=int, help="classify only this PR number")
    args = parser.parse_args()

    corpus = Path(args.corpus)
    prs = sorted(int(p.name) for p in corpus.iterdir() if p.is_dir() and p.name.isdigit())
    if args.pr:
        prs = [args.pr]

    for n in prs:
        pr_dir = corpus / str(n)
        if not (pr_dir / "comments.json").exists():
            print(f"SKIP {n}: no comments.json (run fetch-prs.sh first)", file=sys.stderr)
            continue
        record = classify_pr(pr_dir)
        (pr_dir / "classification.json").write_text(json.dumps(record, indent=2))
        counts = count_labels(record)
        print(f"PR {n}: {counts}")


def count_labels(record):
    counts = {"disputed": 0, "confirmed": 0, "question": 0, "unjudged": 0, "added": 0}
    for rc in record["review_comments"]:
        counts[rc["label"]] = counts.get(rc["label"], 0) + 1
    counts["added"] = len(record["added_findings"])
    return counts


if __name__ == "__main__":
    main()
