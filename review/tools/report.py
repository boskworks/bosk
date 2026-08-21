#!/usr/bin/env python3
"""Build the evidence report from classified corpus records.

Reads corpus/<PR>/classification.json for every classified PR and writes
reports/latest.md: per-PR counts and the raw threads for context.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from collections import Counter
from pathlib import Path

WALL_OF_TEXT_CHARS = 400


def classify_pr(record):
    counts = Counter()
    for rc in record["review_comments"]:
        counts[rc["label"]] += 1
    counts["added"] = len(record["added_findings"])
    return counts


def write_report(corpus: Path, out: Path):
    records = []
    for pr_dir in sorted(corpus.iterdir(), key=lambda p: int(p.name) if p.name.isdigit() else 0):
        cf = pr_dir / "classification.json"
        if cf.exists():
            records.append(json.loads(cf.read_text()))

    lines = []
    lines.append(f"# Review evidence report")
    lines.append(f"_Generated {dt.datetime.now(dt.timezone.utc):%Y-%m-%d %H:%M UTC} from {len(records)} classified PRs._")
    lines.append("")

    totals = Counter()
    for record in records:
        counts = classify_pr(record)
        totals.update(counts)
        n = record["number"]
        lines.append(f"## PR {n}: {record.get('title')}")
        lines.append(f"_disputed {counts['disputed']} · confirmed {counts['confirmed']} · "
                     f"question {counts['question']} · unjudged {counts['unjudged']} · "
                     f"added {counts['added']}_")
        lines.append("")
        for rc in record["review_comments"]:
            tag = rc["label"]
            link = rc.get("html_url") or "#"
            line = f"- [{tag}] {rc['body']} — {rc['path']} — <{link}>"
            if rc.get("replies"):
                line += f"  replies: {rc['replies']}"
            lines.append(line)
        for finding in record["added_findings"]:
            lines.append(f"- [added] {finding['body']} — {finding['path']} — <{finding['html_url']}>")
        for rv in record.get("reviews", []):
            if len(rv["body"]) > WALL_OF_TEXT_CHARS:
                lines.append(f"- **[wall of text]** review body ({len(rv['body'])} chars, "
                             f"state {rv['state']}): {rv['body'][:100]!r}…")
        lines.append("")

    lines.append("## Totals")
    lines.append(f"- disputed: {totals['disputed']}")
    lines.append(f"- confirmed: {totals['confirmed']}")
    lines.append(f"- question: {totals['question']}")
    lines.append(f"- unjudged: {totals['unjudged']}")
    lines.append(f"- added (missed findings): {totals['added']}")
    lines.append("")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n")
    print(f"wrote {out}")


def main():
    parser = argparse.ArgumentParser(description="Build the evidence report.")
    parser.add_argument("--corpus", default=str(Path(__file__).resolve().parent.parent / "data" / "corpus"))
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "data" / "reports" / "latest.md"))
    args = parser.parse_args()
    write_report(Path(args.corpus), Path(args.out))


if __name__ == "__main__":
    main()
