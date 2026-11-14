---
name: pr-description
description: Write or rewrite a pull request title and description that lead with the improvement the change delivers, support it with facts, and don't overclaim. Use when asked to write, revise, or critique a PR description or title.
---

A reviewer reads the description to decide whether to care, and then whether to believe you. It should answer both questions, in that order.

## Workflow

1. **Write a draft of the description.** Say what the change is and why it matters, in whatever order it
   comes out. Don't try to structure it yet; the steps below reshape it.

2. **Write the summary sentence.** Imagine the draft is finished, and you add one more sentence at the end
   that starts with "In short...". Write that sentence. It says what changed and why it matters, rather
   than how the code works; if you can't write it, the change has no clear point yet.

3. **Reorder the sentence so the improvement comes first.** Ask which half you could delete and still
   explain why the PR is an improvement. The answer should be the second half; if it's the first, the
   sentence is backwards. "An update is not lost during a refurbish, because the write names the format it
   was planned against" is in order. "Because the write names the format it was planned against, an update
   is not lost during a refurbish" is not.

4. **Narrow the claim to what the change covers.** Replace "never", "always" and "even" with the situation
   you actually addressed.

5. **List the facts that support the claim**, before writing any prose:

   - what is now covered that wasn't
   - the mechanism that makes it possible
   - what it costs, and where
   - what it deliberately doesn't affect
   - how it is tested
   - what old limitation it retires

   Cut any fact that doesn't support the claim.

6. **Write the paragraphs.** Start with the summary sentence, then one paragraph per group of related facts.
   Give the tests their own section, and the measurements their own section if the PR contributes any.
   Don't list the commits.

7. **Write the title for someone scanning release notes.** Start from the summary sentence and keep its
   scope, but assume the reader is skimming a long list of changes and deciding which ones affect them.
   Name the situation and the effect, and leave out implementation detail they would need the description
   to understand.

8. **Review the draft and delete what doesn't contribute.** Read it clause by clause and ask what each one
   adds. Delete sentences that restate the summary, explain mechanism a reviewer would not question, or
   repeat something said elsewhere. Delete whole sentences; don't shorten them into fragments.

## Check the draft

- Does it open with the change, or with a fact about the system?
- Does it say why this matters, or only what the code does?
- Is the point at the end of a sentence rather than the beginning?
- Does it claim "never", "always", or "even"?
- Is there a vivid verb where a plain one would be clearer?
- Does a word claim more than the interface does? ("acknowledged" for a driver that does not acknowledge
  anything synchronously)
- Does a section exist only because the template has one?
- Would someone skimming a list of changes know whether this one affects them?

## Language

- Plain, informal, concrete. Say what the change is and why it matters; don't restate the diff.
- No em dashes.
- Don't say the tests pass or that it compiles. The PR runs them, so the description has no reason to
  mention the result.
- Link issues, PRs, commits, and earlier investigations instead of alluding to them.
- Keep the description current as later commits change what the PR does.

## Applying it

Post with a body file so the formatting survives, written in the session's scratch directory:

```
gh pr edit <PR> --title '...' --body-file <path>
```
