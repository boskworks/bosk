#!/usr/bin/env python3
"""Harvest a maintainer's review comments from a repository.

Finds PRs reviewed by --author in --repo, fetches that author's review
comments and review bodies, filters out trivial ones, and writes them to --out
as JSON. Comments are listed with their PR and path so they can be curated
into the judge's few-shot examples. See PLAN.md.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

TRIVIAL = {"lgtm", "+1", "-1", "thanks", "thank you", "agree", "agreed", "done", "ok"}


def gh(*args: str) -> str:
    result = subprocess.run(["gh", *args], check=True, capture_output=True, text=True)
    return result.stdout


def substantive(body: str) -> bool:
    text = body.strip().lower()
    return bool(text) and text not in TRIVIAL and not text.startswith("lgtm")


def harvest(repo: str, author: str, limit: int) -> list:
    prs = json.loads(gh("search", "prs", "--reviewed-by", author, "-R", repo,
                        "--limit", str(limit), "--json", "number"))
    records = []
    for pr in prs:
        n = pr["number"]
        comments = json.loads(gh("api", f"repos/{repo}/pulls/{n}/comments"))
        for c in comments:
            if c.get("user", {}).get("login") == author and substantive(c.get("body", "")):
                records.append({
                    "repo": repo, "pr": n, "path": c.get("path"),
                    "html_url": c.get("html_url"), "body": c["body"],
                })
        reviews = json.loads(gh("api", f"repos/{repo}/pulls/{n}/reviews"))
        for r in reviews:
            if r.get("user", {}).get("login") == author and substantive(r.get("body", "")):
                records.append({
                    "repo": repo, "pr": n, "path": None,
                    "html_url": r.get("html_url"), "body": r["body"],
                })
    return records


def main():
    parser = argparse.ArgumentParser(description="Harvest a maintainer's review comments.")
    parser.add_argument("--repo", required=True, help="OWNER/REPO")
    parser.add_argument("--author", default="prdoyle")
    parser.add_argument("--limit", type=int, default=30, help="max PRs to scan (default 30)")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    records = harvest(args.repo, args.author, args.limit)
    args.out.write_text(json.dumps(records, indent=2) + "\n")
    print(f"harvested {len(records)} comments from {args.repo} -> {args.out}")


if __name__ == "__main__":
    main()
