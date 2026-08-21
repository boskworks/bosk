---
name: pr-review
description: Review a pull request
---
Review a pull request on behalf of the maintainer.

1. Load `review/prompts/review.md` and follow it exactly.
2. Review the requested PR in its full context: the diff plus the surrounding repository code and conventions.
3. Produce the review as the JSON document described in the prompt file and write it to the requested path. Do not post anything yourself.
4. If the user asked for the review to be posted, post it with `review/tools/post_review.py <PR> <path>`; otherwise leave it unposted.
