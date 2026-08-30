#!/usr/bin/env python3
"""The recall matcher: which review comments capture which gold findings.

This is an instrument of the eval workflow, imported by eval.py rather than run
as its own script; its name is underscored because Python cannot import a module
whose name contains a dash. It is validated by the calibrate workflow against
the expert's reactions.
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

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
    """Return the last complete JSON object in the response.

    The model is instructed to output nothing but the JSON, but its reasoning or
    dumped tool output can precede or follow it with stray braces or prose; the
    last complete object is the answer.
    """
    decoder = json.JSONDecoder()
    candidates = []
    i = 0
    while True:
        i = text.find("{", i)
        if i < 0:
            break
        try:
            obj, end = decoder.raw_decode(text, i)
            candidates.append((end, obj))
        except json.JSONDecodeError:
            pass
        i += 1
    if not candidates:
        raise RuntimeError("no JSON object found in match response")
    end, obj = max(candidates)
    return obj
