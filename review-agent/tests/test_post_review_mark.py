"""Tests for the reviewer marker on summaries and comments (review/post-review.py)."""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "review" / "post-review.py"
SPEC = importlib.util.spec_from_file_location("post_review", SCRIPT)
post_review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(post_review)


def review(summary: str = "No blocking findings", comments: list[dict] | None = None) -> dict:
    return {
        "summary": summary,
        "verdict": "COMMENT",
        "comments": comments or [],
    }


class MarkTest(unittest.TestCase):
    def test_summary_is_marked(self):
        payload = post_review.review_payload(review(), mark=True)
        self.assertEqual(payload["body"], "[review] No blocking findings")

    def test_summary_is_not_marked_when_disabled(self):
        payload = post_review.review_payload(review(), mark=False)
        self.assertEqual(payload["body"], "No blocking findings")

    def test_summary_is_not_double_marked(self):
        payload = post_review.review_payload(review("[review] No blocking findings"), mark=True)
        self.assertEqual(payload["body"], "[review] No blocking findings")

    def test_comments_are_marked(self):
        payload = post_review.review_payload(
            review(comments=[{"path": "A.java", "line": 1, "body": "Please double-check this."}]),
            mark=True,
        )
        self.assertEqual(payload["comments"][0]["body"], "[review] Please double-check this.")

    def test_comments_are_not_marked_when_disabled(self):
        payload = post_review.review_payload(
            review(comments=[{"path": "A.java", "line": 1, "body": "Please double-check this."}]),
            mark=False,
        )
        self.assertEqual(payload["comments"][0]["body"], "Please double-check this.")


if __name__ == "__main__":
    unittest.main()
