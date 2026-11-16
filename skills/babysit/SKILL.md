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
- Merging stays with the user unless the request grants it. Closing and rewriting reviewed history stay with the user.

## Start

1. Resolve the PR: the number or URL the user gave, else `gh pr view --json number,url` for the current branch. Link it with `link_pull_request`.
2. Read the repository's agent instructions (`AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`) for commit, verification, and PR rules. Every fix below follows them.
3. Handle the current state as one wake (open review comments, failing checks, conflicts).
4. Arm the watch with `watch_pull_request` and end the turn with a one-line status.

Without the T3 tools, run `gh pr checks <n> --watch` as a background command that re-invokes you on exit, and tell the user that review comments will not wake the thread. If the harness cannot re-invoke you, tell the user that babysitting needs T3.

## Wake

Handle every new event in the wake message, then end the turn. A wake is done when each failed check has a pushed fix, a rerun, or a question to the user; each new comment has a pushed fix with its thread resolved, an answer, or a pending question; and the watch is still armed.

- **Check failed**: read the failing job log (`gh run view <run> --log-failed`) and find the root cause. Fix it, run the same check locally, commit, push. A failure that also occurs on the base branch is outside the PR: report it instead of fixing it. A clearly flaky infrastructure failure gets one `gh run rerun <run> --failed`.
- **Review or comment**: sort each point.
  - *Clear fix*: implement, verify, push, then resolve the thread. Write no reply: comments are for questions and answers, not for "done".
  - *Question about intent*: answer from the code, posted if replies are granted, otherwise drafted. The thread stays open for the reviewer.
  - *Design decision* (behaviour, UX, API shape, scope, or a trade-off the repository does not settle): ask the user through the harness's input tool call (`AskUserQuestion` in Claude Code), giving options and a recommendation; a plain question ending the turn only where no such tool exists. Handle the clear fixes of the same wake first, so their CI runs while the user decides. Implement, push, and resolve after the answer, again without a reply.
  - Resolve with `gh api graphql -f query='mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}' -f id=<thread id>`; thread ids come from the PR's `reviewThreads` GraphQL field.
- **Conflict with the base**: update the branch the way the repository does it. A rebase gets a backup ref first and a `--force-with-lease` push.
- **Required checks green**: if the PR is approved and mergeable, tell the user it is ready to merge. Keep watching; the user merges.
  With a merge grant, and no change request open: wait 30 seconds with a one-off background command, then read the PR state. Merged: finish. Still approved and green: merge with the repository's method. Anything else: keep watching.
- **Merged or closed**: call `unwatch_pull_request` and finish with a summary of fixes pushed, text posted or still drafted, and decisions the user made.

## Status

One line per wake: what arrived, what you did, and any drafts awaiting approval. Waiting needs no narration; T3 wakes the thread.
