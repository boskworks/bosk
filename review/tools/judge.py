#!/usr/bin/env python3
"""Run the review judge (prompts/judge.md) on a review JSON.

The judge evaluates a review the way the principal expert would, by
interpolating over the expert's demonstrated behavior in its few-shot examples.
This script feeds it the judge prompt, the review under evaluation, and the PR
context the reviewer saw. It is a triage tool: the judge's `likely_disputed`
and `no_evidence` comments are for the maintainer to eyeball, not authoritative
verdicts. See PLAN.md.
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

DEFAULT_MODEL = "deepseek/deepseek-v4-flash"


def main():
    parser = argparse.ArgumentParser(description="Run the review judge on a review JSON.")
    parser.add_argument("pr_dir", type=Path, help="corpus/<PR> containing the PR context")
    parser.add_argument("review", type=Path, help="path to the review JSON to judge")
    parser.add_argument("--repo-dir", default=".", help="the repository worktree")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--out", help="write the judge's verdict to this path (default: stdout)")
    args = parser.parse_args()

    judge_prompt = (Path(__file__).resolve().parent.parent / "prompts" / "judge.md").read_text()
    context = args.pr_dir / "virtual" / "context.diff"
    context_text = context.read_text() if context.exists() else "No review-time diff available."

    instruction = (
        judge_prompt
        + "\n\n## Review under evaluation\n"
        + args.review.read_text()
        + "\n\n## Context\n"
        + context_text
        + "\n\nRender your verdict in the output format described above."
    )
    cmd = ["opencode", "run", "--auto", "--dir", str(args.repo_dir), "--model", args.model, instruction]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=900)
    output = result.stdout.strip()
    if args.out:
        Path(args.out).write_text(output + "\n")
    else:
        print(output)


if __name__ == "__main__":
    main()
