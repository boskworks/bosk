#!/usr/bin/env python3
"""Offline evaluation of the review prompt.

Generates a review JSON per (PR, prompt) by running the review agent headlessly
via `opencode run` (no posting), then scores recall against that PR's gold
findings: the expert's own added review comments. Matching is semantic: the
model decides whether a review comment captures a gold finding, since string
similarity misses reworded matches. Virtual reviews are cached under
corpus/<PR>/virtual/<prompt-hash>.json, match results alongside.

The context given to the agent reproduces the PR's review-time state: one
`git diff <merge-base(PR.base.sha, anchor)>..<anchor>` section per review
anchor. See PLAN.md for the reconstruction rules and the reachability guard.

The matcher's verdicts are not trusted as a measurement until validated against
the expert's reactions (PLAN.md, Option B); recall is reported meanwhile as an
unvalidated matcher reading. Precision is not measured here at all: a virtual
review has no expert reactions, so "would the expert dispute this comment?"
needs the calibrated judge, which is a later phase. See PLAN.md.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

DEFAULT_MODEL = "deepseek/deepseek-v4-flash"
DEFAULT_JOBS = 4


def hash_prompt(prompt_path: Path) -> str:
    digest = subprocess.run(["git", "hash-object", str(prompt_path)], check=True,
                            capture_output=True, text=True).stdout.strip()
    return digest[:10]


def model_slug(model: str) -> str:
    return model.replace("/", "-").replace(".", "-")


def write_context(pr_dir: Path, repo_dir: Path) -> Path:
    """Materialize the PR's review-time change sets, plus the head commit, for the agent.

    The maintainer's gold findings were made against the PR as it was at each
    review moment, so the agent must see that same material. For merged PRs we
    reconstruct the review-time diff for each review anchor (the commit a review
    comment was made against): `git diff $(git merge-base(base.sha, anchor))..anchor`.
    The recorded base.sha is a base-lineage tip that the anchor's fork point
    resolves against, unchanged by later rebases. For non-merged PRs the anchors
    live in the current head, so `gh pr diff` covers everything.
    """
    n = pr_dir.name
    pr = json.loads((pr_dir / "pr.json").read_text())
    repo = subprocess.run(["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
                          check=True, capture_output=True, text=True, cwd=repo_dir).stdout.strip()
    head = subprocess.run(["gh", "api", f"repos/{repo}/pulls/{n}", "--jq", ".head.sha"],
                          check=True, capture_output=True, text=True, cwd=repo_dir).stdout.strip()
    ctx_dir = pr_dir / "virtual"
    ctx_dir.mkdir(parents=True, exist_ok=True)
    ctx = ctx_dir / "context.diff"
    if pr.get("merged"):
        base_sha = pr["base"]["sha"]
        comments = json.loads((pr_dir / "comments.json").read_text())
        anchors = sorted({c["original_commit_id"] for c in comments if c.get("original_commit_id")})
        parts = []
        for anchor in anchors:
            fork = subprocess.run(["git", "-C", str(repo_dir), "merge-base", base_sha, anchor],
                                  check=True, capture_output=True, text=True).stdout.strip()
            diff = subprocess.run(["git", "-C", str(repo_dir), "diff", fork, anchor],
                                  check=True, capture_output=True, text=True).stdout
            parts.append(f"# anchor {anchor}\n{diff}")
        body = "\n".join(parts)
        if not body:
            body = subprocess.run(["gh", "pr", "diff", n], check=True, capture_output=True, text=True,
                                  cwd=repo_dir).stdout
    else:
        body = subprocess.run(["gh", "pr", "diff", n], check=True, capture_output=True, text=True,
                              cwd=repo_dir).stdout
    ctx.write_text(f"# head {head}\n{body}\n")
    return ctx


def generate_review(repo_dir: Path, model: str, pr: int, context_file: Path, out: Path, log: Path) -> None:
    instruction = (
        f"Review PR {pr} using the pr-review skill. Read the PR's review-time change sets from "
        f"{context_file}: it is divided into sections, one per review anchor (each headed '# anchor "
        f"<sha>'). Examine repository files at the anchor commit of the section they appear in with "
        f"`git show <sha>:<path>`. The anchor commit is the review-time state of the PR; use it as the "
        f"source for your analysis. Only for files that are not present at any anchor commit, read them at "
        f"the head commit instead. Produce the review as the JSON document described in "
        f"review/prompts/review.md and write it to {out}. Do not post anything."
    )
    cmd = ["opencode", "run", "--auto", "--dir", str(repo_dir), "--model", model, "--format", "json", instruction]
    with log.open("w") as f:
        subprocess.run(cmd, check=True, stdout=f, stderr=subprocess.STDOUT, timeout=1800)
    if not out.exists():
        raise RuntimeError(f"{out} was not written by the review agent (see {log})")


MATCH_INSTRUCTION = (
    "You are matching the findings of a maintainer against an automated review of the same pull request. "
    "For each gold finding, decide whether the review captures it: whether it makes the same point, "
    "possibly reworded or anchored at a different line. Match on substance, not wording. Respond with a "
    'single JSON object of the form {"matched": [{"finding": 0, "comment": 2}], "unmatched": [1]} where '
    '"finding" is an index into gold_findings and "comment" an index into review_comments. Each finding '
    "matches at most one comment. Output nothing but that JSON.\n\n"
)


def match_findings(repo_dir: Path, model: str, gold_bodies: list, comments: list, log: Path) -> list:
    """Ask the model which review comments cover which gold findings. Returns the 'matched' list.

    The model occasionally rambles or its call fails; retry once before giving up.
    """
    payload = {
        "gold_findings": gold_bodies,
        "review_comments": [
            {"i": i, "path": c.get("path"), "line": c.get("line"), "body": c["body"]}
            for i, c in enumerate(comments)
        ],
    }
    cmd = ["opencode", "run", "--auto", "--dir", str(repo_dir), "--model", model,
           MATCH_INSTRUCTION + json.dumps(payload)]
    last_error = None
    for _ in range(2):
        try:
            with log.open("w") as f:
                subprocess.run(cmd, check=True, stdout=f, stderr=subprocess.STDOUT, timeout=600)
            return extract_json(log.read_text()).get("matched", [])
        except (RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
            last_error = e
    raise RuntimeError(f"match failed after 2 attempts: {last_error}")


def extract_json(text: str) -> dict:
    """Return the last parseable JSON object in the response.

    The model is instructed to output nothing but the JSON, but its reasoning or
    dumped tool output can precede it with stray braces; the final object is the
    answer.
    """
    i = len(text)
    while True:
        i = text.rfind("{", 0, i)
        if i < 0:
            break
        try:
            return json.loads(text[i:])
        except json.JSONDecodeError:
            continue
    raise RuntimeError("no JSON object found in match response")


def context_files(pr_dir: Path):
    """The set of file paths in the reconstructed review-time context, or None if unknown."""
    ctx = pr_dir / "virtual" / "context.diff"
    if not ctx.exists():
        return None
    files = set()
    for line in ctx.read_text().splitlines():
        if line.startswith("diff --git "):
            files.add(line.split(" b/")[-1])
    return files


def evaluate_one(pr_dir: Path, repo_dir: Path, model: str, prompt_hash: str, refresh: bool) -> tuple:
    """Generate, match and score one PR's virtual review. Returns (name, recalled, total, report_lines, note).

    Recall is measured against the gold findings whose file is present in the
    reconstructed review-time context (the reachability guard); findings on
    files the reviewer could not have seen are excluded from the denominator.
    """
    n = pr_dir.name
    try:
        record = json.loads((pr_dir / "classification.json").read_text())
        all_gold = record.get("added_findings", [])
        if not all_gold:
            return n, 0, 0, [], "skip (no gold findings)"
        reachable_files = context_files(pr_dir)
        if reachable_files is not None:
            gold = [f for f in all_gold if f["path"] in reachable_files]
            excluded = [f for f in all_gold if f["path"] not in reachable_files]
        else:
            gold = all_gold
            excluded = []
        if not gold:
            return n, 0, 0, [f"## PR {n}", "_no reachable findings_", ""], None
        virtual_dir = pr_dir / "virtual"
        virtual_dir.mkdir(parents=True, exist_ok=True)
        base = f"{prompt_hash}-{model_slug(model)}"
        out = virtual_dir / f"{base}.json"
        match_out = virtual_dir / f"{base}.match.json"
        if out.exists() and not refresh:
            print(f"PR {n}: reuse cached {out}")
        else:
            ctx = write_context(pr_dir, repo_dir)
            generate_review(repo_dir, model, int(n), ctx, out, virtual_dir / f"{base}.log")
        review = json.loads(out.read_text())
        comments = review.get("comments", [])
        if match_out.exists() and not refresh:
            matched = json.loads(match_out.read_text()).get("matched", [])
        else:
            matched = match_findings(repo_dir, model, [f["body"] for f in gold], comments,
                                     virtual_dir / f"{base}.match.log")
            match_out.write_text(json.dumps({"matched": matched}))
        matched_indices = {m["finding"] for m in matched}
        recalled = len(matched_indices)
        total = len(gold)
        missed = [f for i, f in enumerate(gold) if i not in matched_indices]
        lines = [f"## PR {n}", f"_recall {recalled}/{total} ({recalled / total:.0%})_"]
        for f in excluded:
            lines.append(f"- EXCLUDED (file not in review-time context): {f['body']}")
        lines += [f"- MISSED: {f['body']}" for f in missed]
        lines.append("")
        return n, recalled, total, lines, None
    except Exception as e:
        print(f"PR {n}: generation failed: {e}", file=sys.stderr)
        return n, 0, 0, [f"## PR {n}: FAILED ({e})", ""], f"failed: {e}"


def main():
    parser = argparse.ArgumentParser(description="Offline eval of the review prompt (recall vs gold findings).")
    parser.add_argument("--corpus", default=str(Path(__file__).resolve().parent.parent / "data" / "corpus"))
    parser.add_argument("--repo-dir", default=".", help="the repository worktree the review agent runs in")
    parser.add_argument("--prompt", default=str(Path(__file__).resolve().parent.parent / "prompts" / "review.md"))
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--prs", help="comma-separated PR numbers to evaluate (default: all classified)")
    parser.add_argument("--jobs", type=int, default=DEFAULT_JOBS,
                        help="how many review generations to run in parallel (default %(default)s)")
    parser.add_argument("--refresh", action="store_true", help="regenerate existing virtual reviews")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "data" / "reports" / "eval.md"))
    args = parser.parse_args()

    corpus = Path(args.corpus)
    repo_dir = Path(args.repo_dir)
    prompt_hash = hash_prompt(Path(args.prompt))

    pr_dirs = sorted(p for p in corpus.iterdir() if p.name.isdigit() and (p / "classification.json").exists())
    if args.prs:
        wanted = {int(n) for n in args.prs.split(",")}
        pr_dirs = [p for p in pr_dirs if int(p.name) in wanted]

    results = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = [pool.submit(evaluate_one, pr_dir, repo_dir, args.model, prompt_hash, args.refresh)
                   for pr_dir in pr_dirs]
        for fut in as_completed(futures):
            results.append(fut.result())

    results.sort(key=lambda r: int(r[0]))
    lines = [f"# Offline eval — prompt {prompt_hash} ({args.model}, {args.jobs} jobs)",
             f"_Generated {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}_", ""]
    total_recalled = total_gold = succeeded = 0
    for n, recalled, total, pr_lines, note in results:
        if note:
            print(f"PR {n}: {note}")
            continue
        succeeded += 1
        print(f"PR {n}: recall {recalled}/{total}")
        total_recalled += recalled
        total_gold += total
        lines += pr_lines

    overall = f"{total_recalled}/{total_gold} ({total_recalled / total_gold:.0%})" if total_gold else "n/a"
    lines.append(f"## Overall recall: {overall} ({succeeded}/{len(results)} PRs evaluated)")
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines) + "\n")
    print(f"overall recall {overall}; wrote {out_path}")


if __name__ == "__main__":
    main()
