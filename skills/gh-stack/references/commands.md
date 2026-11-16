# `gh stack` command reference

Use this file for command selection. Confirm flags with `gh stack <command> --help` before mutating state because the extension is in public preview.

## Setup and inspection

| Goal | Command | Important behavior |
| --- | --- | --- |
| Install the official extension | `gh extension install github/gh-stack` | Modify the user-level GitHub CLI installation only with authorization. |
| Check authentication | `gh auth status` | Stop and report if authentication is unavailable. |
| Show extension version | `gh stack --version` | Record the version when diagnosing changed behavior. |
| Show the active stack | `gh stack view` | Display branches, order, PRs, and latest commits. |
| Emit machine-readable state | `gh stack view --json` | Prefer for verification and scripted inspection. |
| Check out a remote or local stack | `gh stack checkout <stack-or-pr>` | Fetch and establish local tracking when necessary. |

## Build and publish

| Goal | Command | Important behavior |
| --- | --- | --- |
| Create or adopt a stack | `gh stack init --base <trunk> <bottom> [...]` | Adopt existing named branches or create missing ones. Enable Git rerere. |
| Add a top layer | `gh stack add <branch>` | Require checkout of the current top layer. Create the branch at current `HEAD`. |
| Publish the stack | `gh stack submit` | Push branches, create or update PRs, and create native stack linkage. |
| Publish non-interactively | `gh stack submit --auto` | Create new PRs as drafts unless `--open` is supplied. Review generated metadata afterward. |
| Link existing PRs remotely | `gh stack link --base <trunk> <bottom-pr> <top-pr>` | Supply bottom-to-top order. Correct mismatched PR bases. Do not create local tracking. |

## Update and repair

| Goal | Command | Important behavior |
| --- | --- | --- |
| Reconcile everything routinely | `gh stack sync` | Fetch, reconcile remote composition, update trunk, optionally cascade when trunk moved, push, and sync PR state. Never create PRs. |
| Cascade changed ancestry | `gh stack rebase` | Fetch and rebase every layer from trunk upward. Handle already-merged lower PRs with `--onto` semantics. |
| Cascade only above current layer | `gh stack rebase --upstack` | Use after changing the current lower or middle layer. |
| Continue conflict resolution | `gh stack rebase --continue` | Stage resolved files first. |
| Restore before the rebase | `gh stack rebase --abort` | Abort the entire cascading operation. |
| Push rewritten layers | `gh stack push` | Use explicit force-with-lease per branch. Exclude merged and queued branches. The multi-branch push is not atomic. |
| Restructure interactively | `gh stack modify` | Require a clean, linear stack with no queued PR. Apply changes with `Ctrl+S`. Submit afterward if remote PRs exist. |
| Remove stack tracking and linkage | `gh stack unstack` | Preserve PRs and branches, but remove stack linkage. Treat as a remote write. |
| Remove only local tracking | `gh stack unstack --local` | Leave GitHub stack linkage intact. |

## Navigate

| Goal | Command |
| --- | --- |
| Move one layer away from trunk | `gh stack up` |
| Move one layer toward trunk | `gh stack down` |
| Jump to top or bottom | `gh stack top` / `gh stack bottom` |
| Jump to trunk | `gh stack trunk` |
| Pick a layer interactively | `gh stack switch` |

## Merge semantics

- Run `gh stack merge` to choose a cutoff layer interactively.
- Run `gh stack merge <pr-number>` to merge from the bottom through that PR.
- Run `gh stack merge --yes --squash` only when the repository permits squash and authorization is explicit.
- Expect no PR to merge if any selected layer fails normal stack merge requirements.
- Expect `gh stack merge` to enqueue selected layers instead of directly merging when the trunk requires a merge queue. Let the queue choose the merge method.
- Do not expect stacked-PR mechanics to resolve real Git or semantic conflicts.

## Official references

- Introduction and web workflow: <https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-stacked-pull-requests>
- Complete CLI reference: <https://docs.github.com/en/pull-requests/reference/stacked-prs-cli-commands>
- Extension documentation: <https://github.github.com/gh-stack/>
