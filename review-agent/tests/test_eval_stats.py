"""Tests for the eval's per-generation stats extraction."""
from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "eval"))
from eval import extract_stats, generation_slowdown_note  # noqa: E402


def write_stats(dir: pathlib.Path, pr: str, duration: float) -> pathlib.Path:
    p = dir / f"{pr}.stats.json"
    p.write_text(json.dumps({"duration_seconds": duration}))
    return p


class GenerationSlowdownNoteTest(unittest.TestCase):
    def test_flags_more_than_2x_slower(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            archive = root / "archive-20260830-0000" / "key"
            archive.mkdir(parents=True)
            run = root / "key"
            run.mkdir()
            write_stats(archive, "435", 400.0)
            write_stats(run, "435", 1000.0)
            note = generation_slowdown_note(archive, run, "435")
        self.assertIsNotNone(note)
        self.assertIn("2.5x", note)

    def test_no_flag_within_factor(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            archive = root / "archive-20260830-0000" / "key"
            archive.mkdir(parents=True)
            run = root / "key"
            run.mkdir()
            write_stats(archive, "435", 400.0)
            write_stats(run, "435", 500.0)
            note = generation_slowdown_note(archive, run, "435")
        self.assertIsNone(note)

    def test_no_flag_without_prior(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            archive = root / "archive-20260830-0000" / "key"
            archive.mkdir(parents=True)
            run = root / "key"
            run.mkdir()
            write_stats(run, "435", 1000.0)
            note = generation_slowdown_note(archive, run, "435")
        self.assertIsNone(note)


class ExtractStatsTest(unittest.TestCase):
    def test_parses_tokens_cost_and_duration(self):
        log_lines = [
            {"type": "step_start", "timestamp": 1_000_000},
            {"type": "tool_use", "timestamp": 1_100_000, "part": {"type": "tool", "tool": "read"}},
            {"type": "step_finish", "timestamp": 1_200_000,
             "part": {"type": "step-finish", "tokens": {"total": 100, "output": 10, "reasoning": 5},
                      "cost": 0.001}},
            {"type": "text", "timestamp": 1_300_000,
             "part": {"type": "text", "time": {"start": 1_250_000, "end": 1_300_000}}},
            {"type": "step_finish", "timestamp": 1_400_000,
             "part": {"type": "step-finish", "tokens": {"total": 200, "output": 20, "reasoning": 8},
                      "cost": 0.002}},
        ]
        with tempfile.TemporaryDirectory() as d:
            log = pathlib.Path(d) / "generation.log"
            log.write_text("\n".join(json.dumps(line) for line in log_lines) + "\n")
            stats = extract_stats(log, {"files": ["a.java"], "diffs": [{"diff": "a\nb\nc\n"}]}, 1000)
        self.assertEqual(stats["duration_seconds"], 400.0)
        self.assertEqual(stats["steps"], 2)
        self.assertEqual(stats["tool_calls"], {"read": 1})
        self.assertEqual(stats["tokens"], {"total": 200, "output": 20, "reasoning": 8})
        self.assertAlmostEqual(stats["cost"], 0.003)
        self.assertEqual(stats["assistant_turn_seconds"], [50.0])
        self.assertEqual(stats["snapshot_bytes"], 1000)
        self.assertEqual(stats["files"], 1)
        self.assertEqual(stats["diff_lines"], 3)
        self.assertEqual(stats["tokens_per_second"], 0.5)


if __name__ == "__main__":
    unittest.main()
