---
name: ready-check
description: Validate a branch before its PR goes ready for review, with a change ledger that justifies every part of the diff and an independent minimal-diff pass. Use before creating a PR ready for review, before marking a draft ready, or when the user asks whether a diff is all necessary.
---

# Ready check

The user sees why every part of the diff exists and what could still go, before any reviewer does. Work read-only until they decide in step 5.

## 1. Confirm the pipeline is green

Read the CI workflows (`.github/workflows/`) and run locally every check they run for the modules the branch touches: linters and style checks such as Checkstyle, formatter checks, build, and tests. Use each project's own commands, on the OS that holds the checkout. Report a red check as a finding; its fix waits for the user's decision in step 5.

Done when every relevant CI check has run locally and is green, or each red check is listed with its output.

## 2. Pin the base and the requirements

1. `git fetch origin`, then base = `git merge-base origin/<default> HEAD`. A stale local default branch is the classic way to review code that is already merged.
2. Diff = `git diff <base>...HEAD`. If the worktree is dirty, include those changes and say so.
3. Requirements: the linked issue (PR body, commit messages, branch name; fetch it with `gh`), the PR description, and the task from the conversation. Ask the user when none exists.

Done when you have the base SHA, `git diff --stat`, and a numbered requirement list.

## 3. Build the change ledger

Group the diff into **change units**: one logical change each, possibly spanning files. For every unit record:

- what it does, and its +/− lines
- the requirement it serves (quote it), or the unit it supports
- verdict: **needed** (a requirement demands it), **supporting** (a needed unit depends on it), **optional** (everything works without it)

Then fill these lists, each entry with its reason, or "none":

- new files
- new dependencies and build plugins
- new or changed public API
- new abstractions: types, interfaces, wrappers, hooks, settings
- new or changed tests
- changes outside the feature's area, formatter-only changes included

Done when every hunk belongs to exactly one unit: the units' line counts add up to the diff stat.

## 4. Minimal-diff pass

Pick the **reviewers**: the review pair that the user's instructions name. Where they name none, look at what is installed (`codex --version`, `claude --version`, under T3 Code `orchestrator_capabilities`) and take one model of another provider; with your own provider alone, a fresh subagent on another of its models, or on your own model when it offers no other.

Delegate a read-only review to each reviewer through T3 `delegate_task`, the CLI (`codex exec`, `claude -p`), or your own subagent tool. Give each the base, the diff command, the requirements, and your ledger. Their one question:

> What can be removed or simplified while every requirement still holds? For each proposal, show the smaller alternative (exact deletion or code) and the lines saved. Contest any ledger verdict you disagree with.

If quota or an outage blocks a reviewer, follow the quota rule in the user's instructions, or continue with the remaining one where they have none; with none left, pick again from what is installed. In a `ship` run, follow its quota rule instead.

Check each proposal against the code yourself and mark it **accept** or **reject**, with a reason when you reject.

Done when every proposal carries a mark.

## 5. Report, then wait

Show the user:

- base SHA and diff stat
- the ledger as a table: unit | +/− | requirement | verdict
- the pipeline result from step 1
- the fixed lists from step 3
- the models that reviewed, then the accepted cuts with lines saved; rejected proposals in one line each

End the turn and wait for the user's decision. Apply the cuts they pick, rerun the pipeline from step 1 plus screenshots for visual changes, and show the new diff stat. Create the PR ready for review, or mark the draft ready, only after the user's explicit OK and with the pipeline green.

In a `ship` run, skip the wait: apply the accepted cuts, rerun the pipeline, and return to `ship`.
