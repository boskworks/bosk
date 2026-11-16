---
name: pr-description
description: Write or rewrite a pull request title and description that report the change, support it with facts, and don't overclaim. Use when asked to write, revise, or critique a PR description or title.
---

A reviewer reads the description to decide whether to care, and then whether to believe you. It should answer both questions, in that order.

A description is an account of a change, not a description of the finished system. "The house is white" is true and tells you nothing; "Painted the house white, since it was too dark" is a change. The reviewer has the old code in their head, or none of it, and wants to know what you did and what difference it made.

## Workflow

1. **Write a draft of the description.** Say what the change is and why it matters, in whatever order it
   comes out. Don't try to structure it yet; the steps below reshape it.

2. **Write the summary sentence.** Imagine the draft is finished, and you add one more sentence at the end
   that starts with "In short...". Write that sentence. It says what changed and why it matters, rather
   than how the code works; if you can't write it, the change has no clear point yet.

3. **Open with what you did.** The verb is past tense: "Added `Reference.isDeletable()`, and every driver
   now rejects `submitDeletion` for a reference that fails it." The second half can be present tense when
   it gives the effect in a few words; what matters is that the sentence reports something you did.
   "`Reference.isDeletable()` reports whether a reference can be deleted" fails that test: it is a fact
   about the codebase that happens to be new.

4. **Say how it was before.** Past tense, and specific enough to recognise: which call misbehaved, what it
   did instead, what a user saw. "Only the root was checked. Deleting a record field went through to the
   driver, and each driver failed its own way: ..." A reviewer who already knew the problem can skip this
   paragraph; one who didn't can't trust the rest without it.

5. **Say what is true now.** Present tense, for the part of the system the change is responsible for, in a
   sentence or two: "A reference is deletable if its last segment is a Catalog, Listing, or SideTable
   entry, an Optional value, or a Phantom."

   Two sentences to delete on sight:

   - One that announces a capability: "lets a caller find out before submitting".
   - One that narrates the diff: "the check moved onto the reference", "this exposes the path compiler's
     rule". Say what the code does, not where it lives.

6. **Narrow the claim to what the change covers.** Replace "never", "always" and "even" with the situation
   you actually addressed.

7. **List the facts that support the claim**, before writing any prose:

   - what is now covered that wasn't
   - the mechanism that makes it possible
   - what it costs, and where
   - what it deliberately doesn't affect
   - what old limitation it retires
   - anything about the tests that the diff doesn't show

   Cut any fact that doesn't support the claim.

8. **Write the paragraphs.** Start with the paragraphs from steps 3 to 5, then one paragraph per group of
   related facts. Don't list the commits or the test classes; the diff names them. If there is something
   about the tests that a reviewer couldn't see in the diff, say that instead.

9. **Write the title for someone scanning release notes.** Use the imperative, like the rest of the log
   ("Avoid losing updates during a refurbish"), and keep the scope of the summary sentence. Leave out
   implementation detail they would need the description to understand.

10. **Review the draft and delete what doesn't contribute.** Read it clause by clause and ask what each one
    adds. Delete sentences that restate the summary, explain mechanism a reviewer would not question, or
    repeat something said elsewhere. Delete whole sentences; don't shorten them into fragments.

## Check the draft

- Does the first sentence say what you did?
- Does it say what was wrong before?
- Could the draft sit in the user documentation unchanged, with no sign that anything changed? Then it
  describes the system rather than the change.
- Does it say why this matters, or only what the code does?
- Does it claim "never", "always", or "even"?
- Is there a fancy verb where a plain one would do?
- Does a word claim more than the interface does? ("acknowledged" for a driver that does not acknowledge
  anything synchronously)
- Does a section exist only because the template has one, or only to show that work was done?
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
