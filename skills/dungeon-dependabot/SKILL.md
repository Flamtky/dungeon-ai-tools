---
name: dungeon-dependabot
description: Merge workflow for Dependabot PRs in the Dungeon repo (Dungeon-CampusMinden/Dungeon): API-impact check per PR, CI, approve, squash-merge, conflicts via Dependabot rebase. Use when the user asks to merge or go through the Dependabot / dependency-update PRs in Dungeon, e.g. "merge die Dependabot-PRs".
---

# Dungeon Dependabot merge

Merge every open Dependabot PR. Bumps are expected to need no code change; the API check exists to catch the rare one that does. The request authorizes assigning, approving, closing and reopening, `@dependabot` comments, and squash-merging these PRs. Dependabot owns its branches: changes to them go through `@dependabot` commands.

## 1. Collect the batch

```sh
gh pr list --author app/dependabot --state open --json number,title,headRefName,headRefOid,assignees,files
```

Leave out a PR that is assigned to someone other than you (`gh api user --jq .login`) and name it in the report. The others are the **batch**; under T3 Code, link each with `link_pull_request` and arm `watch_pull_request` on it, so a conflict or a failing check after a merge wakes you. The batch is done when every PR in it is merged, closed by Dependabot as superseded, or skipped with a reason.

## 2. API check, per PR

Read its comments and reviews first (`gh pr view <n> --json comments,reviews`); skip a PR with a human discussion that has not settled, such as a request to hold it, and quote it in the report. Assign every other PR to the user (`gh pr edit <n> --add-assignee '@me'`): the user owns the merge and what follows. Then read the release notes and changelog in the PR body (`gh pr view <n> --json body`) for the whole version range; follow the linked changelog when the body is truncated. Then find where the repo touches the dependency:

- Gradle library or plugin (`dependencies.gradle`, `build.gradle`): imports of its packages and calls into the changed APIs.
- npm in `wizard/spec-generator`: a dependency only in `package-lock.json`, not in `package.json`, is transitive and needs no further check.
- Docker image (`tracking/`), GitHub Action (`.github/workflows/`), Gradle wrapper: the tags, inputs, and flags the repo uses.

A PR is **critical** when a breaking or behaviour change from the notes hits code or config the repo uses; a major bump gets its migration guide read in full. Skip a critical PR and report the affected `file:line`, the change, and the proposed adaptation; the user decides.

## 3. CI

CI is green when every check on the PR's head SHA succeeded or was skipped; no checks yet means pending. Under T3 Code, the PR watch from step 1 wakes you when checks finish; outside T3, wait with `gh pr checks <n> --watch` as a background command (it exits at once while no checks exist). Check the other PRs meanwhile.

`master` requires checks that only `pull_request` runs satisfy; a manually dispatched workflow never counts. When the PR reports no checks although its head commit is older than 15 minutes (`gh pr view <n> --json commits --jq '.commits[-1].committedDate'`), GitHub has removed its runs: once the API check has passed and `gh pr view <n> --json mergeable` reports `MERGEABLE`, close and reopen it, which starts the checks on the same head. A conflicting PR gets `@dependabot rebase` first (step 4); a younger head or `UNKNOWN` gets another look once the other PRs are handled.

```sh
gh pr close <n>
gh pr reopen <n>
```

`@dependabot rebase` is for conflicts (step 4): on a branch that is up to date it pushes nothing, so it starts no checks.

A failed run: read `gh run view <id> --log-failed`. A failure caused by the bump makes the PR critical (step 2); a flaky infrastructure failure gets one `gh run rerun <id> --failed`; a failure that the same check shows for the same reason on other current PRs is outside the bump (`master` runs no PR checks; compare with the latest merged PR): report it and skip the PR.

## 4. Merge, one at a time

```sh
gh pr review <n> --approve
gh pr merge <n> --squash
```

`master` requires one approving review. Approve only a green PR, with an empty body.

After each merge, take the next green PR as it is: a PR behind `master` needs no update. Only a conflict (`gh pr view <n> --json mergeable` reports `CONFLICTING`) does: comment `@dependabot rebase`, continue with the other PRs, and return once Dependabot has pushed; the new head SHA needs green CI again (step 3). Under T3 Code, the PR watch wakes you when the push starts the checks; outside T3, wait for the new head with a background command (timeout 30 minutes):

```sh
until [ "$(gh pr view <n> --json headRefOid --jq .headRefOid)" != "<old headRefOid>" ]; do sleep 60; done
```

## 5. Report

Under T3 Code, call `unwatch_pull_request` on every PR of the batch; a merged PR can stay watched and wake you later. Then one line per PR: merged, or skipped with the reason. Critical findings in full.
