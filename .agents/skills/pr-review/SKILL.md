---
name: pr-review
description: Review a pull request
---
Review a pull request on behalf of the maintainer.

1. Load `review/prompts/review.md` and follow it exactly.
2. Review the requested PR in its full context: the diff plus the surrounding repository code and conventions.
3. Produce the review as the JSON document described in the prompt file and write it to the requested path.
4. Post it with `review/tools/post_review.py <PR> <path>`. The maintainer usually asks for a review expecting it to be posted, so default to posting; only hold off, and say why, if you're clearly unsure that's what they want.
