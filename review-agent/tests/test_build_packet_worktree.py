"""Tests for packet worktree reconciliation (corpus/build-packet.py).

Packets are reused across review rounds, so a rebuilt packet must move an
existing worktree to the requested head; otherwise it silently reviews the
previous round's code. These tests use a throwaway git repository, so they are
hermetic and need no network.
"""
from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "corpus" / "build-packet.py"
SPEC = importlib.util.spec_from_file_location("build_packet", SCRIPT)
build_packet = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_packet)


def git(*args: str, cwd: Path) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


class WorktreeReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        git("init", "--quiet", cwd=self.repo)
        git("config", "user.email", "test@example.com", cwd=self.repo)
        git("config", "user.name", "Test", cwd=self.repo)
        (self.repo / "file.txt").write_text("first\n")
        git("add", "file.txt", cwd=self.repo)
        git("commit", "--quiet", "-m", "first", cwd=self.repo)
        self.old = git("rev-parse", "HEAD", cwd=self.repo)
        (self.repo / "file.txt").write_text("second\n")
        git("commit", "--quiet", "-am", "second", cwd=self.repo)
        self.new = git("rev-parse", "HEAD", cwd=self.repo)

    def tearDown(self):
        self.tmp.cleanup()

    def make_worktree(self, head: str) -> Path:
        worktree = self.root / "packet" / "worktree"
        worktree.parent.mkdir(parents=True, exist_ok=True)
        git("worktree", "add", "--detach", str(worktree), head, cwd=self.repo)
        return worktree

    def head_of(self, worktree: Path) -> str:
        return git("rev-parse", "HEAD", cwd=worktree)

    def test_moves_existing_worktree_to_requested_head(self):
        worktree = self.make_worktree(self.old)
        build_packet.ensure_worktree(worktree, self.new, self.repo)
        self.assertEqual(self.head_of(worktree), self.new)

    def test_leaves_worktree_already_at_requested_head_alone(self):
        worktree = self.make_worktree(self.new)
        build_packet.ensure_worktree(worktree, self.new, self.repo)
        self.assertEqual(self.head_of(worktree), self.new)

    def test_refuses_to_discard_uncommitted_changes(self):
        worktree = self.make_worktree(self.old)
        (worktree / "file.txt").write_text("reviewer experiment\n")
        with self.assertRaises(SystemExit) as raised:
            build_packet.ensure_worktree(worktree, self.new, self.repo)
        self.assertIn("uncommitted changes", str(raised.exception))
        self.assertEqual(self.head_of(worktree), self.old)
        self.assertEqual((worktree / "file.txt").read_text(), "reviewer experiment\n")

    def test_refuses_directory_that_is_not_a_worktree(self):
        worktree = self.root / "packet" / "worktree"
        worktree.mkdir(parents=True)
        with self.assertRaises(SystemExit) as raised:
            build_packet.ensure_worktree(worktree, self.new, self.repo)
        self.assertIn("not a usable git worktree", str(raised.exception))

    def test_missing_commit_fails_before_touching_the_worktree(self):
        worktree = self.make_worktree(self.old)
        with self.assertRaises(SystemExit) as raised:
            build_packet.ensure_worktree(worktree, "0" * 40, self.repo)
        self.assertIn("is not in", str(raised.exception))
        self.assertEqual(self.head_of(worktree), self.old)


if __name__ == "__main__":
    unittest.main()
