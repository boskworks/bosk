---
name: pr-reviewer
description: Review a pull request on behalf of the maintainer as the review agent: build the review packet, produce and post the review, verify the author's responses, and re-review until clean. Use when asked to review a pull request.
---
Review a pull request on behalf of the maintainer, and carry the review through the full cycle until the
PR is clean or the maintainer takes over.

## Discipline

Each round of this cycle is produced according to a discipline intended to help make the reviewer more
effective: we use purpose-built scripts instead of interacting with GitHub directly. The agent's input is
a review packet built by script, and its output is JSON written to a path; the only thing that touches
GitHub is the posting script. Keeping each round a pure function of the packet is what makes it
reproducible and evaluable.

## The review cycle

1. **Review.** Build the review packet for the PR first: `review-agent/corpus/fetch-prs.sh --pr <PR>` to
   bring the PR's current data into the corpus, then `review-agent/corpus/build-packet.py <PR>` to check
   out the reviewed commit in a worktree and snapshot its GitHub information. Then load
   `review-agent/review/reviewer.md` and follow it exactly, reviewing against the packet: the repository
   is checked out at the reviewed commit in `review-agent/data/packets/<PR>/worktree`, and the PR's
   GitHub information is in `review-agent/data/packets/<PR>/snapshot.json`. Produce the review as the JSON
   document described in the prompt file and write it to the requested path.
2. **Post.** Post it with `review-agent/review/post-review.py <PR> <path>`. The maintainer usually asks for a
   review expecting it to be posted, so default to posting; only hold off, and say why, if you're clearly
   unsure that's what they want.
   - Approval is the handoff signal: the maintainer treats `APPROVE` as their cue to do their own review,
     so post it only for a clean review (no findings). On the author's own account, GitHub refuses
     `APPROVE` and `REQUEST_CHANGES`; fall back to `COMMENT` and say so.
3. **Verify responses.** When the maintainer asks to check the responses (for example, "check the responses
   again, resolving the conversation thread if you agree or replying if you disagree"), do it for every
   thread under the discipline above:
   - Refresh the packet so its snapshot reflects the current thread state:
     `review-agent/corpus/fetch-prs.sh --pr <PR>`, then `review-agent/corpus/build-packet.py <PR>`. Group
     the snapshot's comments by `in_reply_to_id`; each group is a thread.
   - For every reply claiming a fix ("Fixed in <sha>"), confirm the code at that commit actually does
     what the reply says — read the diff, and run the module's targeted tests if that's cheap. Verify with
     evidence, not by trusting the claim.
   - Decide for each thread: either reply confirming what you verified (name the commit and the property
     you checked), or refute with specific reasoning. There is no third option, and no thread gets skipped.
   - Write the decisions as a responses JSON to `review-agent/data/responses/<PR>.json`: a `responses`
     array with one entry per thread, each `{"comment_id": <int>, "reply": "<string>", "resolve": <bool>}`,
     where `comment_id` is the thread's root comment id from the snapshot and `resolve` marks accepted
     threads. Post it with `review-agent/review/post-responses.py <PR> <path>`, which is the only thing
     that touches GitHub. The posting script stamps every reply with the `[review]` marker (the reviewer
     and the author share an account), so do not include the marker in the responses JSON yourself.
     Thread resolution is yours alone: the author's skill leaves threads open, so set `resolve: true`
     only after you have verified the response actually addresses the comment.
4. **Fresh review.** When every thread is resolved, run the full review again at the new head — fixes can
   introduce new issues — and repeat the cycle. A clean pass posts `APPROVE` (handing off to the
   maintainer); anything else continues the cycle.

## Suggest improvements

When the cycle is finished, if you noticed anything that would make the skill or the reviewer prompt better —
a blind spot, a workflow snag, a faster way to verify — say so. These skills are a work in progress;
suggestions from real cycles are how they improve.
