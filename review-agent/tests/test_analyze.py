"""Tests for the eval analysis tool's audit (outside-packet detection)."""
from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "eval"))
from analyze import outside_packet_calls  # noqa: E402


def tool(tool: str, **input_fields) -> dict:
    return {"type": "tool_use", "part": {"type": "tool", "tool": tool,
                                         "state": {"input": input_fields}}}


class OutsidePacketTest(unittest.TestCase):
    def test_flags_git_archaeology_and_outside_reads(self):
        base = "/x/worktrees/pr-review-assistant/"
        lines = [
            json.dumps(tool("read", filePath=base + "review-agent/data/packets/414-r1/worktree/a.java")),
            json.dumps(tool("read", filePath=base + "review-agent/review/reviewer.md")),
            json.dumps(tool("read", filePath=base + "review-agent/data/eval/key/414.json")),
            json.dumps(tool("bash", command="git log --oneline -5")),
            json.dumps(tool("bash", command="git hash-object /x/reviewer.md")),
            json.dumps(tool("bash", command="ls " + base + "review-agent/data/corpus/")),
            json.dumps(tool("bash", command="grep -r foo .")),
        ]
        with tempfile.TemporaryDirectory() as d:
            log = pathlib.Path(d) / "414.log"
            log.write_text("\n".join(lines) + "\n")
            flagged = outside_packet_calls(log)
        self.assertEqual(flagged, [
            "read outside packet: review-agent/data/eval/key/414.json",
            "git archaeology: git log --oneline -5",
            "outside packet: ls /x/worktrees/pr-review-assistant/review-agent/data/corpus/",
        ])


if __name__ == "__main__":
    unittest.main()
