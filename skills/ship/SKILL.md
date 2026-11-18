---
name: ship
description: Take an issue, bug, or feature from order to merged PR on its own, after one round of pinning down the order.
disable-model-invocation: true
---

# Ship

The user invokes this skill to get a change merged. After step 1, run to the merge alone; the only planned stop is the test list in step 8. Invoking it grants commit, push, PR, ready for review, one reviewer, babysitting, replies on the PR, rebasing, and the merge, for this order only.

## The order

- **Goal**: issue, bug, feature, or spec.
- **Location** (required): this machine or a linked one. If it is missing, ask before any other work. Without T3 Code it is always this machine.
- **Optional**: reviewer, follow-ups after the merge.

## 1. Pin the order

Read the source. Find the main checkout with `git worktree list` and read its agent instructions and every local plan listed in `.git/info/exclude`.

Write the requirements and an acceptance list. Each acceptance item names something observable, with a number where one exists (1080p, no scrollbar from the task area).

- Unclear requirement: `grilling`, all questions in one round (without that skill, ask them yourself in one round).
- More than one PR: write a local spec with the PR cut, agree it with the user, then ship the PRs in order.
- Other machine: launch a thread there in its own worktree, on the model the user's instructions name for it (your own where they name none), without `delegatedFrom`. The brief starts with `/ship` (Codex: `$ship`), says that step 1 is done and the thread continues at step 2, and contains the order, the acceptance list, and the content of the plans it needs, since local paths do not exist there. The launched worktree takes the place of step 2's.
- Without T3 Code, a review does not wake this thread: say in this round that after step 8 the user resumes the thread once the reviewer has answered. On resume, read checks, reviews, and mergeability afresh.

Done when every requirement has an acceptance item and a way to check it, and every decision you can foresee is settled.

## 2. Branch

Create a worktree and a new branch from freshly fetched `origin/<default>`.

## 3. Reproduce (bugs only)

Run `diagnosing-bugs` under the conditions in the report (without that skill, build a loop that reproduces the symptom yourself). Done when the loop goes red on the reported symptom. If it cannot, say so and mark any fix as unverified.

## 4. Implement

Commit each coherent unit, signed where the user's setup requires it. Bugs in the touched area come along whatever their size. Everything else becomes a note in the report.

## 5. Verify

Check every acceptance item in the real app: the project's play or run skill, Playwright against the real backend, screenshots for visual changes.

When an item cannot be observed directly (feel, audio, real hardware), look for indirect proof first and name what it shows. What stays open goes on the test list.

Done when each item has an observed result or sits on the test list with a reason.

## 6. Review

Run a review by each reviewer, picked as in step 4 of `ready-check`. Check every finding against the code, fix the real ones, and have substantial fixes reviewed again.

Done when a round brings no new real finding. After three rounds, hand the open findings to the user.

## 7. Ready check

Run `ready-check`. Fix every bug it finds, apply the accepted cuts, and rerun the pipeline until it is green.

## 8. Open the PR

Fetch and compare the base. If it moved or a stacked parent merged, rebase with a backup ref first. Open the PR in the repository's style, assign the user (`@me`), attach screenshots, and under T3 Code link it to the thread.

If the test list has entries, leave the PR as a draft, provide a test build and the list, and wait. Feedback returns to step 4. The user's OK continues below.

Mark it ready, request exactly one reviewer (the named one, else the best fit from the history of the touched files, within the project's rules), and run `babysit` with the merge granted. More than one reviewer needs the user's word.

After the merge, do the follow-ups from the order and, under T3 Code, unwatch the PR.

## Thread updates

The user follows the run in this thread; the thread alone tells them what happened, without opening the PR. Post an update here when a step ends, in the same message as your next action, and keep working:

- Step 3: what went red, the root cause, the planned fix.
- Step 5: each acceptance item with its result, and before/after screenshots embedded as Markdown.
- Steps 6 and 7: each finding or proposal with its verdict and reason.
- Step 8: the PR link, the reviewer and why.
- Each babysit wake that changes something: what arrived and what you did.

Every decision goes into the update of its step: the options, your choice, the reason.

## Decisions

After step 1, decide everything yourself, including design questions from reviews: take the option with the smallest diff that is easiest to reverse, and record it in the PR description and in the thread. Answer reviewers on the PR yourself. This replaces babysit's asking.

If a reviewer of the pair has no quota, retry every 30 minutes with a one-off background command for up to five hours, the usual length of a limit window. Then run the review with the remaining one alone or, with none left, pick again from what is installed. In a `ship` run this replaces the quota rule in the user's instructions. Mention it only in the report in this thread, and leave it out of everything on GitHub.

Wait for the user only when you cannot continue: signing fails, access is missing, or data without a backup would be lost. Name what you need.

End only processes you started, by PID.

## Report

Post the report when the PR goes ready for review, and again after the merge with what changed since: what shipped, how each acceptance item was checked, what stays unverified, the notes from step 4, and the change ledger.
