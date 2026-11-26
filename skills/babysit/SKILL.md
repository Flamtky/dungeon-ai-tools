---
name: babysit
description: Babysit a pull request until it merges: wait for checks, reviews, and other async events, fix what fails, handle feedback, and ask the user about design decisions. Use when the user asks to babysit, watch, or monitor a PR.
---

# Babysit

Keep a pull request moving until it is merged or closed. The thread sleeps between events; each **wake** gets handled completely, then the turn ends.

## Permissions

The babysit request sets them, for this PR only:

- **Code**: commits and pushes of fixes to the PR's head branch, and reruns of its CI jobs, are part of babysitting.
- **Visible text on GitHub** (replies to questions, comments, PR title edits, description changes beyond the next point, new reviewers, labels): only the kinds the user explicitly granted in this request or earlier in the conversation. Without a grant, write the text as a **draft** in your status and post it once the user approves.
- **Re-requesting a review** from a reviewer whose review you addressed needs no grant: once every thread of that review is fixed and pushed, request it again with `gh pr edit <n> --add-reviewer <login>`.
- **PR description**: when a pushed fix makes it inaccurate, update the affected part without asking; it describes the branch, so keeping it current is part of the fix.
- **Resolving a review thread** posts no text: resolve it once its point is fixed in a pushed commit. A resolved thread needs no reply; the commit and the resolution say it is done.
- **Merging** a **settled** PR (see Wake) is part of babysitting, unless the request keeps the merge with the user. Only an explicit statement in the babysit request or later counts; earlier remarks, such as one made while the PR was a draft, do not. Closing and rewriting reviewed history stay with the user.

## Start

1. Resolve the PR: the number or URL the user gave, else `gh pr view --json number,url` for the current branch. Link it with `link_pull_request`.
2. Read the repository's agent instructions (`AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`) for commit, verification, and PR rules. Every fix below follows them.
3. Handle the current state as one wake (open review comments, failing checks, conflicts).
4. Arm the watch with `watch_pull_request` and `until: "merged"`, so the thread stays waiting until the merge, then end the turn with a one-line status.

Without the T3 tools, run `gh pr checks <n> --watch` as a background command that re-invokes you on exit, and tell the user that review comments will not wake the thread. If the harness cannot re-invoke you, tell the user that babysitting needs T3.

## Wake

Handle every new event in the wake message, then end the turn. A wake is done when each failed check has a pushed fix, a rerun, or a question to the user; each new comment has a pushed fix with its thread resolved, an answer, or a pending question; a settled PR is merged or reported ready; and the watch on an open PR is still armed.

- **Check failed**: read the failing job log (`gh run view <run> --log-failed`) and find the root cause. Fix it, run the same check locally, commit, push. A failure that also occurs on the base branch is outside the PR: report it instead of fixing it. A clearly flaky infrastructure failure gets one `gh run rerun <run> --failed`.
- **Review or comment**: sort each point.
  - *Clear fix*: implement, verify, push, then resolve the thread. Write no reply: comments are for questions and answers, not for "done".
  - *Question about intent*: answer from the code, posted if replies are granted, otherwise drafted. The thread stays open for the reviewer.
  - *Design decision* (behaviour, UX, API shape, scope, or a trade-off the repository does not settle): ask the user through the harness's input tool call (`AskUserQuestion` in Claude Code), giving options and a recommendation; a plain question ending the turn only where no such tool exists. Handle the clear fixes of the same wake first, so their CI runs while the user decides. Implement, push, and resolve after the answer, again without a reply.
  - Resolve with `gh api graphql -f query='mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}' -f id=<thread id>`; thread ids come from the PR's `reviewThreads` GraphQL field.
- **Conflict with the base**: update the branch the way the repository does it. A rebase gets a backup ref first and a `--force-with-lease` push.
- **Settled**: after the events of every wake, read the PR state. The PR is settled when all of these hold:
  - every reviewer who was asked has approved, at least one, and no change request is open. GitHub drops a reviewer from the pending requests with any review, so an asked reviewer who only commented has not approved yet; a comment from someone never asked changes nothing.
  - nothing is open: every review thread is resolved, every question on the PR is answered, and no question to the user and no draft waits for the user.
  - the PR is open and ready for review, its required checks are green, and GitHub's merge rules are met.

  When the only gap is an asked reviewer who has not reviewed yet, ask the user through the input tool whether to merge without that review or wait, naming the reviewer.

  Where the request keeps the merge with the user: tell them once that the PR is ready, and keep watching. Otherwise wait 30 seconds with a one-off background command and read the state once more. Merged: finish. Still settled: merge with the repository's method. A stack merges once every layer up to the chosen one is settled, with `gh stack merge <top PR number>`, which merges from the bottom up in one step; `gh stack merge` reads a bare number as a stack number first, then as a PR number. Anything else: keep watching.
- **Merged or closed**: call `unwatch_pull_request` and finish with a summary of fixes pushed, text posted or still drafted, and decisions the user made.

## Status

One line per wake: what arrived, what you did, any drafts awaiting approval, and, once every reviewer has approved, what still keeps the PR from being settled. Waiting needs no narration; T3 wakes the thread.
