---
name: pr-author-response
description: Respond to the review comments on your own pull request as the PR author: address or refute each comment, push the fixes, reply to every thread, and resolve the ones you close out. Use when asked to respond to the review comments on a pull request.
---

Respond to the review comments on your own pull request as the PR author.

## Workflow

1. Read the PR and list its review comments:
   `gh api repos/{owner}/{repo}/pulls/{pull_number}/comments --paginate --jq '.[] | {id, in_reply_to_id, user: .user.login, path, line, original_line, original_start_line, diff_hunk, body}'`
   (group them into threads by `in_reply_to_id`; the top-level comment is each thread's root. Note that GitHub normalizes a reply to a reply so its `in_reply_to_id` points at the thread root, not the immediate parent — a reply to the maintainer's follow-up still groups under the original comment).
2. For every comment, either make the requested change (or a better one) or refute the comment with specific reasoning. There is no third option, and no comment gets skipped.
3. Reply to every comment — including the ones you addressed with a change. Say what you changed and where (commit hash), or why you're refuting. Never prefix a reply with `[review]`: that marker belongs to the reviewer, and since you and the reviewer post under the same account, an author reply tagged `[review]` would corrupt the attribution.
4. Sweep for the same class of mistake. A finding usually names one instance of a mistake that recurs: if the reviewer flags a stale doc, a hardcoded path, or a test that doesn't exercise its behavior, look for the same mistake elsewhere and fix the siblings in the same pass, saying in the reply that you swept for it.
5. Push a new commit with the changes, then post the replies.

## Which line a comment references

A comment's `original_line` (or the span `original_start_line`..`original_line`, for a range comment) within its `diff_hunk` shows exactly what the commenter was looking at; read the comment in that context. `line` is the comment's current position and may be null or stale; don't trust it to point at the code. Never guess which code a comment refers to from the conversation.

## Weight of comments

Pay particular attention to comments from the maintainer (login `prdoyle`). They carry more weight than comments from `prdoyle-agent` or any other reviewer: the maintainer's questions and objections are decisions, not suggestions. Address them first and directly.

## Posting discipline

- Use `gh api --jq` for all response extraction. Never pipe `gh` output through `head`/`grep`/`sed`: a pipe hands the exit status to the filter and can truncate the error body, so failures become invisible.
- Reply to a review comment (the pull number is part of the path; omitting it returns 404):
  `gh api repos/{owner}/{repo}/pulls/{pull_number}/comments/{comment_id}/replies --method POST -f body="..."`.
  Do not prefix your reply with `[review]` — that marker is the reviewer's, and you post under the same
  account; your replies must stay distinguishable from the reviewer's.
- Confirm success from the call itself: append `--jq '.id'` to the POST and check the printed id and exit status.
- Gate on the exit status (`set -e`, or `if ! gh api ...; then`). A non-zero exit means the call failed; read the error.
- After posting, verify the end state with a read-back (list the comments again and confirm each reply is on its thread). Only report success after that verification.
- Don't resolve threads. The threads you reply to are the review agent's, and it decides whether your
  response is acceptable and resolves each thread itself after verifying; resolving a thread yourself
  pre-empts that verification.
- If a comment was posted by mistake, delete it: `gh api repos/{owner}/{repo}/pulls/comments/{comment_id} --method DELETE`.
