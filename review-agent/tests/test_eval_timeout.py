"""Tests for the eval's generation-timeout handling.

kill_generation must terminate the whole process tree: opencode can spawn a
child in its own session that a plain killpg would miss, and that child keeps
running (and writing to the generation log) after the timeout fires. This is a
timeout-behaviour test, so it relies on timing by design.
"""
from __future__ import annotations

import subprocess
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "eval"))
from eval import kill_generation


def descendant_pids(pid: int) -> list[int]:
    """All pids descended from pid (including pid), via pgrep -P."""
    tree = []
    frontier = [pid]
    while frontier:
        parent = frontier.pop()
        tree.append(parent)
        kids = subprocess.run(["pgrep", "-P", str(parent)], capture_output=True, text=True).stdout.split()
        frontier += [int(k) for k in kids if k.isdigit()]
    return tree


def alive_pids(pids: list[int]) -> list[int]:
    return [p for p in pids if subprocess.run(["ps", "-o", "pid=", "-p", str(p)],
                                              capture_output=True, text=True).stdout.strip()]


class KillGenerationTest(unittest.TestCase):
    def test_terminates_the_whole_tree(self):
        proc = subprocess.Popen(["bash", "-c", "sleep 60 & sleep 60 & sleep 60"],
                                start_new_session=True)
        time.sleep(0.3)
        tree = descendant_pids(proc.pid)
        self.assertGreater(len(tree), 1, "the test tree should have spawned children")
        kill_generation(proc.pid)
        proc.wait(timeout=5)
        deadline = time.monotonic() + 2.0
        remaining = alive_pids(tree)
        while remaining and time.monotonic() < deadline:
            time.sleep(0.1)
            remaining = alive_pids(tree)
        self.assertEqual(remaining, [], f"these pids survived the kill: {remaining}")


if __name__ == "__main__":
    unittest.main()
