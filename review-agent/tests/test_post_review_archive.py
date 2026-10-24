"""Tests for preserving posted reviews across rounds (review/post-review.py).

The current review stays at data/reviews/<PR>.json so the calibration tools can
find it, while each earlier review moves into data/reviews/archive/<PR>/. These
tests exercise the save logic directly, without posting to GitHub.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "review" / "post-review.py"
SPEC = importlib.util.spec_from_file_location("post_review", SCRIPT)
post_review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(post_review)


def review(summary: str) -> dict:
    return {"summary": summary, "verdict": "COMMENT", "comments": []}


class SaveReviewTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.reviews = Path(self.tmp.name) / "reviews"

    def tearDown(self):
        self.tmp.cleanup()

    def save(self, body: dict, now: str | None = None) -> Path:
        with contextlib.redirect_stdout(io.StringIO()):
            return post_review.save_review(self.reviews, 42, body, now)

    def current(self) -> dict:
        return json.loads((self.reviews / "42.json").read_text())

    def archived(self) -> dict[str, dict]:
        return {path.name: json.loads(path.read_text())
                for path in (self.reviews / "archive" / "42").glob("*.json")}

    def test_first_review_becomes_the_current_one_without_an_archive(self):
        first = review("first")
        path = self.save(first, now="20260101T000000Z")
        self.assertEqual(path, self.reviews / "42.json")
        self.assertEqual(self.current(), first)
        self.assertFalse((self.reviews / "archive").exists())

    def test_posting_again_archives_the_previous_review(self):
        first, second = review("first"), review("second")
        self.save(first, now="20260101T000000Z")
        self.save(second, now="20260102T000000Z")
        self.assertEqual(self.current(), second)
        self.assertEqual(self.archived(), {"20260102T000000Z.json": first})

    def test_successive_reviews_are_all_kept(self):
        bodies = [review("first"), review("second"), review("third")]
        for i, body in enumerate(bodies):
            self.save(body, now=f"2026010{i + 1}T000000Z")
        self.assertEqual(self.current(), bodies[2])
        self.assertEqual(self.archived(), {
            "20260102T000000Z.json": bodies[0],
            "20260103T000000Z.json": bodies[1],
        })

    def test_archive_names_stay_unique_within_a_second(self):
        first, second, third = review("first"), review("second"), review("third")
        for body in (first, second, third):
            self.save(body, now="20260101T000000Z")
        self.assertEqual(self.current(), third)
        self.assertEqual(self.archived(), {
            "20260101T000000Z.json": first,
            "20260101T000000Z-1.json": second,
        })


if __name__ == "__main__":
    unittest.main()
