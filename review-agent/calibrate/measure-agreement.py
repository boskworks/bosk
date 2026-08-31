#!/usr/bin/env python3
"""Measure the judge's agreement with the expert on a reviewed PR.

The core of the calibrate workflow: run the judge (eval/run-judge.py) on a
posted review whose comments the expert has reacted to, compare the judge's
per-comment verdicts to the expert's labels from classification.json, and
report the agreement and the disagreements. Disagreements are the signal for
adjusting eval/judge.md; `no_evidence` and question-labeled comments are
reported but not scored.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL = "deepseek/deepseek-v4-flash"


def normalized(body: str) -> str:
    return " ".join(body.split())


def judge_verdict(pr_dir: Path, review_path: Path, repo_dir: Path, model: str, packets: Path) -> dict:
    cmd = [sys.executable, str(ROOT / "eval" / "run-judge.py"), str(pr_dir), str(review_path),
           "--repo-dir", str(repo_dir), "--model", model, "--packets", str(packets), "--json"]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=900)
    return json.loads(result.stdout)


def build_packet(pr: int, repo_dir: Path, packets: Path) -> None:
    script = ROOT / "corpus" / "build-packet.py"
    subprocess.run([sys.executable, str(script), str(pr), "--round", "1",
                    "--repo-dir", str(repo_dir), "--packets", str(packets)],
                   check=True, capture_output=True, text=True)


def cleanup_packet(pr: int, repo_dir: Path, packets: Path) -> None:
    script = ROOT / "corpus" / "build-packet.py"
    subprocess.run([sys.executable, str(script), str(pr), "--round", "1",
                    "--repo-dir", str(repo_dir), "--packets", str(packets), "--cleanup"],
                   capture_output=True, text=True)


def measure(pr: int, corpus: Path, reviews: Path, repo_dir: Path, model: str, packets: Path) -> str:
    pr_dir = corpus / str(pr)
    review_path = reviews / f"{pr}.json"
    if not review_path.exists():
        sys.exit(f"no archived review at {review_path}: post-review.py archives the review it posts")

    build_packet(pr, repo_dir, packets)
    try:
        classification = json.loads((pr_dir / "classification.json").read_text())
        review = json.loads(review_path.read_text())
        verdict = judge_verdict(pr_dir, review_path, repo_dir, model, packets)

        label_by_key = {(c.get("path"), normalized(c.get("body", ""))): c.get("label")
                        for c in classification["review_comments"]}
        judge_by_index = {x.get("comment"): x for x in verdict.get("comments", [])}

        def score(label: str | None, judge_v: str | None) -> str:
            if judge_v == "no_evidence":
                return "no_evidence"
            if label == "confirmed":
                return "agree" if judge_v == "likely_accepted" else "disagree"
            if label == "disputed":
                return "agree" if judge_v == "likely_disputed" else "disagree"
            return "not_scored"  # question, unjudged, or unlabeled

        counts = {"agree": 0, "disagree": 0, "no_evidence": 0, "not_scored": 0}
        whole = verdict.get("whole_review", {})
        lines = [f"## Judge agreement, PR {pr}",
                 f"_judge whole-review: {whole.get('verdict')} — {whole.get('critique', '')}_", ""]
        for i, c in enumerate(review.get("comments", [])):
            label = label_by_key.get((c.get("path"), normalized(c.get("body", ""))))
            jv = judge_by_index.get(i, {})
            judge_v = jv.get("verdict", "no_evidence")
            outcome = score(label, judge_v)
            counts[outcome] += 1
            mark = {"agree": "OK", "disagree": "!!", "no_evidence": "--", "not_scored": ".."}[outcome]
            lines.append(f"- [{mark}] comment {i}: judge={judge_v} expert={label or 'unlabeled'}")
            if outcome == "disagree":
                lines.append(f"    expert: {c.get('body')}")
                lines.append(f"    judge:  {jv.get('reason', '')}")
        scored = counts["agree"] + counts["disagree"]
        lines.append("")
        lines.append(f"agreement {counts['agree']}/{scored} on scored comments"
                     f" (disagree {counts['disagree']}, no_evidence {counts['no_evidence']}, "
                     f"not scored {counts['not_scored']})")
        return "\n".join(lines)
    finally:
        cleanup_packet(pr, repo_dir, packets)


def main():
    parser = argparse.ArgumentParser(description="Measure the judge's agreement with the expert on a PR.")
    parser.add_argument("pr", type=int)
    parser.add_argument("--corpus", default=str(ROOT / "data" / "corpus"))
    parser.add_argument("--reviews", default=str(ROOT / "data" / "reviews"))
    parser.add_argument("--repo-dir", default=".", help="the repository worktree")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--packets", default=str(ROOT / "data" / "packets"))
    parser.add_argument("--out", help="write the report to this path (default: stdout)")
    args = parser.parse_args()

    report = measure(args.pr, Path(args.corpus), Path(args.reviews), Path(args.repo_dir),
                     args.model, Path(args.packets))
    if args.out:
        Path(args.out).write_text(report + "\n")
    else:
        print(report)


if __name__ == "__main__":
    main()
