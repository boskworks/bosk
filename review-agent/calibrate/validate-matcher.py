#!/usr/bin/env python3
"""Validate the matcher against the expert's engagement on a PR.

The matcher (eval/match_findings.py) decides which review comments capture which
gold findings. Before its recall numbers are trusted, the maintainer checks its
verdicts: this writes a report of the captured and missed pairs (the "validate
before trusting" policy). The pairs come from the review at --review (by default
the archived posted review; point it at a virtual review from the eval cache to
validate the exact pairs that produced recall) and the verdicts at --match (by
default re-run by the matcher; pass the eval's cached match JSON to reuse the
verdicts recall was measured from). The report is a markdown table with a
"Your call" column the maintainer fills in, and it is committed, not stored in
the gitignored data/ cache, because the annotations are the maintainer's
judgment and cannot be reconstructed. See PLAN.md.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL = "deepseek/deepseek-v4-flash"

sys.path.insert(0, str(ROOT / "eval"))
from match_findings import match_findings  # noqa: E402

sys.stdout.reconfigure(line_buffering=True)


def load_matched(match_path: Path) -> dict:
    """The cached match verdicts, as {finding: comment}."""
    matched = json.loads(match_path.read_text()).get("matched", [])
    return {m["finding"]: m["comment"] for m in matched}


def run_matcher(repo_dir: Path, model: str, gold: list, comments: list) -> dict:
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
        log = Path(f.name)
    try:
        matched = match_findings(repo_dir, model, gold, comments, log)
    finally:
        log.unlink(missing_ok=True)
    return {m["finding"]: m["comment"] for m in matched}


def cell(text: str) -> str:
    """A markdown table cell: escape pipes, collapse newlines."""
    return text.replace("|", "\\|").replace("\n", " ")


def existing_calls(out: Path) -> dict:
    """The {row: your call} annotations already in a report.

    Regeneration carries them forward, so the maintainer's judgments are never
    clobbered by a re-run.
    """
    calls = {}
    if not out.exists():
        return calls
    for line in out.read_text().splitlines():
        parts = [p.strip() for p in line.strip().strip("|").split("|")]
        if parts and parts[0].isdigit() and len(parts) == 5:
            calls[int(parts[0])] = parts[4]
    return calls


def write_report(out: Path, pr: int, review_path: Path, match_source: str,
                 findings: list, comments: list, by_finding: dict) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    calls = existing_calls(out)
    lines = [
        f"# Matcher validation, PR {pr}",
        "",
        f"Source review: `{review_path}`. Matcher verdicts: {match_source}.",
        "",
        "The matcher decides which review comments capture each gold finding; "
        "recall is measured from these verdicts. For each row, record in the "
        "`Your call` column whether the matcher's verdict is right: `correct` "
        "or `wrong`, plus an optional note. For a `missed` verdict, scan the "
        "review comments below to confirm none captures the finding. Recall is "
        "not trusted until these are checked.",
        "",
        f"Captured {len(by_finding)}/{len(findings)} findings.",
        "",
        "| # | Your finding | Reviewer comment | Matcher verdict | Your call |",
        "|---|---|---|---|---|",
    ]
    for i, finding in enumerate(findings):
        your_call = calls.get(i, "")
        j = by_finding.get(i)
        if j is not None:
            comment = comments[j].get("body", "")
            lines.append(f"| {i} | {cell(finding['body'])} | {cell(comment)} | captured | {your_call} |")
        else:
            lines.append(f"| {i} | {cell(finding['body'])} | — | missed | {your_call} |")
    lines += ["", "## Review comments", "",
              "The review's comments the matcher considered, for checking `missed` verdicts.",
              "", "| # | Comment |", "|---|---|"]
    for j, c in enumerate(comments):
        lines.append(f"| {j} | {cell(c.get('body', ''))} |")
    out.write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description="Validate the matcher's verdicts on a PR.")
    parser.add_argument("pr", type=int)
    parser.add_argument("--corpus", default=str(ROOT / "data" / "corpus"))
    parser.add_argument("--reviews", default=str(ROOT / "data" / "reviews"),
                        help="where archived posted reviews live (default %(default)s)")
    parser.add_argument("--review", type=Path,
                        help="review JSON to validate against (default: the archived posted review)")
    parser.add_argument("--match", type=Path,
                        help="cached match verdicts JSON to reuse (default: re-run the matcher)")
    parser.add_argument("--out", type=Path,
                        help="markdown report path (default: %(default)s)")
    parser.add_argument("--repo-dir", default=".")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    args = parser.parse_args()

    pr_dir = Path(args.corpus) / str(args.pr)
    review_path = args.review or (Path(args.reviews) / f"{args.pr}.json")
    if not review_path.exists():
        sys.exit(f"no review at {review_path}: pass --review, or post/archive a review first")
    classification = json.loads((pr_dir / "classification.json").read_text())
    findings = classification["added_findings"]
    gold = [f["body"] for f in findings]
    if not gold:
        sys.exit(f"PR {args.pr} has no added findings to validate against")
    comments = json.loads(review_path.read_text()).get("comments", [])

    if args.match:
        by_finding = load_matched(args.match)
        match_source = f"cached (`{args.match}`)"
    else:
        by_finding = run_matcher(Path(args.repo_dir), args.model, gold, comments)
        match_source = "fresh matcher run"

    out = args.out or (ROOT / "reports" / "matcher" / f"{args.pr}.md")
    write_report(out, args.pr, review_path, match_source, findings, comments, by_finding)

    print(f"PR {args.pr}: captured {len(by_finding)}/{len(gold)} findings; wrote {out}")
    print(f"matcher verdicts: {match_source}")


if __name__ == "__main__":
    main()
