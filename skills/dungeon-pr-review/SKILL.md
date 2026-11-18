---
name: dungeon-pr-review
description: Review workflow for pull requests in the Dungeon repo (Dungeon-CampusMinden/Dungeon): local review, optional second pass by another model, GitHub review draft in the user's style, posted only on request, re-reviews after updates. Use whenever the user requests a review of a Dungeon PR, e.g. "review PR 3255", "lokaler review von pr …", "guck dir den PR an", or attaches a PR reference and asks for findings.
---

# Dungeon PR review

The review stays read-only until the user explicitly asks to post it; the one exception is the approval that ends a re-review under the conditions in step 7. The user's own observations come first: answer each one, then add findings of your own.

## 1. Load the PR without touching any checkout

```sh
gh pr view <N> --json title,body,headRefName,baseRefName,headRefOid,state,files,comments,reviews,closingIssuesReferences
gh api repos/Dungeon-CampusMinden/Dungeon/pulls/<N>/comments
git fetch origin +pull/<N>/head:pr-<N> master
git merge-base origin/master pr-<N>
git diff <base> pr-<N>
```

`<base>` is the SHA that `git merge-base` prints. Read the linked issue (`gh issue view <number> --comments`) and the existing review threads before your own pass. Read files at PR head with `git show pr-<N>:<path>` and at the base with `git show <base>:<path>`. Never check the PR out in the user's working tree or worktree; the branch there may be renamed or in use.

## 2. Start the second pass in the background

A model of another provider reviews the PR alongside you. Take its provider, model, and effort from the model table in the user's instructions: the row for critical reviews. This step needs T3 Code and such a row; without either, skip it and say in the report that the review had one pass. Run it right away through the T3 MCP (`delegate_task`), not a CLI, and do your own pass (step 3) while it works:

```json
{
  "title": "PR <N> second review",
  "role": "review",
  "mode": "async",
  "clientRequestId": "pr<N>-second-review-<round>",
  "target": {"providerInstanceId": "<provider>", "model": "<model>",
             "options": {"<effort option>": "<effort>"}},
  "task": "<prompt>"
}
```

`orchestrator_capabilities` names each provider's models and its effort option. Keep the returned `taskId`. Its completion wakes this thread, so end the turn instead of polling; read the result with `task_status`. Each further round is a new `delegate_task` call with its own `clientRequestId`.

The prompt contains: PR number, base SHA and `pr-<N>` ref with the `git diff` / `git show` commands, the PR intent from its description, each of the user's points with the request to judge it (agree/disagree, why, cleaner alternative in this codebase, and whether the code already existed at `<base>`), your own suspicions as a checklist, and the rules: read-only, only report findings verified in code, each with file:line, concrete failure scenario, and severity; skip style nits; answer in German, concisely.

## 3. Do your own pass

- For each user point: find out *why* the code is there (which bug it prevents) before agreeing it should go. Then show what removing it would break and what the clean replacement looks like.
- Check `git show <base>:<path>` to tell new code from code the PR only moved or re-indented. Mark pre-existing issues as such.
- Trace cross-cutting paths end to end: input handling (`InputManager` → stage listeners → `InputSystem` callbacks), dialog ownership (local entity vs. synced hero) and `DialogCallbackResolver` routing, client vs. server authority.
- Check these lenses: changes the feature does not need, needless complexity or abstraction, existing APIs and patterns left unused (`DungeonLogger`, existing assets and components), fully qualified class names instead of imports, nullable public returns instead of `Optional`, and tests beyond the central behaviour of the change: a test whose assertion another test already covers, a further input for the same branch, an edge case the change does not create, bulk checks of content. Name the tests that can go and the ones that carry the change, and the central behaviour the change introduces that no test covers.
- When a conclusion depends on library behaviour, read the library source instead of guessing. libGDX sources live in `~/.gradle/caches/modules-2/files-2.1/com.badlogicgames.gdx/gdx/<version>/*/gdx-<version>-sources.jar`.

## 4. Merge and report

Verify each second-pass finding in code yourself; drop what you cannot confirm, or label it unproven. Report in German:

- the answer to each user point (one short section each),
- remaining findings as a table with P1–P3, pre-existing issues marked,
- what was checked and found fine, in one line,
- the note that everything is static (nothing run).

Done when every user point has an answer and every finding is verified or labelled.

## 5. Draft the GitHub review

Only the findings the user marks as relevant go in; when the user asks for the draft without marking any, every verified finding goes in. Write the draft outside every checkout (`~/.t3/worktrees/Dungeon/review-pr-<N>.md` under T3 Code, else the system temp folder), in this format (the payload script parses it):

````md
# Review-Entwurf PR #<N> – <Titel>

**Status:** Request changes

**Body:**

Ein paar Punkte habe ich gefunden, vor allem beim ESC-Handling.

---

**`game/src/.../InputManager.java:75-81`**

Kommentartext …

```suggestion
            return oldProcessor != null && oldProcessor.keyDown(keycode);
```
````

Line numbers are PR-head lines (`git show pr-<N>:<path> | grep -n`). A `suggestion` replaces exactly the commented range, so span every line it rewrites. File-level comments are not possible in a review payload; anchor those to the class declaration line.

### Review style

The draft goes out under the user's account, so it reads like the user's own reviews. When `STYLE.local.md` exists in this skill folder, follow it. Otherwise read the user's past reviews (`<login>` from `gh api user --jq .login`) and match their language, length, and tone; when they have none, ask the user for the language and tone:

```sh
gh api graphql -f query='query{ search(query:"repo:Dungeon-CampusMinden/Dungeon is:pr reviewed-by:<login> -author:app/dependabot", type:ISSUE, first:20){ nodes{ ... on PullRequest{ number reviews(author:"<login>", first:5){ nodes{ state body comments(first:20){ nodes{ path line body } } } } } } } }'
```

## 6. Post (only when the user asks)

Build the payload with [`scripts/build_review_payload.py`](scripts/build_review_payload.py) from this skill folder (`python3` on Linux and macOS), writing it to a temporary file:

```sh
python <skill folder>/scripts/build_review_payload.py \
  <draft.md> <headRefOid> REQUEST_CHANGES <payload.json>
gh api -X POST repos/Dungeon-CampusMinden/Dungeon/pulls/<N>/reviews --input <payload.json>
```

Use `COMMENT` or `APPROVE` as the event when the draft's status says so. Afterwards, under T3 Code, link the PR to the thread with `link_pull_request`, and report the review URL.

## 7. Re-review after updates

Review only what changed since your last review. The last reviewed SHA is the `commit_id` of your last review in `gh api repos/Dungeon-CampusMinden/Dungeon/pulls/<N>/reviews`. Run `git fetch origin +pull/<N>/head:pr-<N>`, then `git log <last reviewed SHA>..pr-<N>` and `git diff <last reviewed SHA> pr-<N>`. Check every new commit, and that each earlier point is fixed or answered. Report in German, as in step 4.

Approve with an empty body when all of these hold: the user asked for this re-review and did not ask for it read-only, the PR is still open (`gh pr view <N> --json state`), your previous round is posted on GitHub, and every point from it is fixed or answered with no unproven finding left. Otherwise report and wait. Merge (squash) only when the user asks and the required checks are green.
