---
name: gh-stack
description: Decide when dependent changes should use GitHub stacked pull requests, then create, adopt, inspect, update, rebase, publish, link, modify, and merge stacks with the official `gh stack` CLI. Use when asked whether pull requests should be stacked; to work with `gh stack`, stacked PRs, dependent branches, cascading rebases, stack synchronization, or stack merging; to turn existing branches or PRs into a native GitHub stack; or to explain how stacks interact with squash merges and merge queues.
---

# GitHub Stacked Pull Requests

Use native GitHub stacks to split dependent changes into reviewable layers while preserving their required order.

## Apply the operating rules

- Follow repository-specific GitHub, commit, signing, and PR rules first.
- Treat questions, explanations, and inspections as read-only. Obtain authorization from the task or the user before pushing branches, creating or changing PRs, linking or unstacking PRs, or merging.
- Use the authenticated GitHub CLI. Do not substitute a GitHub connector when repository rules require `gh`.
- Before changing a stack, inspect `git status --short --branch`, the current branch, remotes, the default branch, and `gh stack view`. Preserve unrelated or uncommitted work.
- Run `gh auth status`, `gh stack --version`, and the relevant `gh stack <command> --help` before execution when availability or syntax is uncertain.
- Treat command details as unstable because native stacked PRs are in public preview. Prefer the installed CLI help, then the official documentation.
- Require every branch in a native stack to live in the same repository. Do not construct cross-fork stacks.
- Never add or preserve the generated `Stack created with GitHub Stacks CLI` attribution or feedback footer in PR bodies. Remove it after submission when the CLI inserts it; native stack linkage does not depend on body text.

## Decide whether to use a stack

Use a stack when all of these hold:

- A later change genuinely depends on an earlier change.
- Separating the changes materially improves reviewability or ownership.
- Each layer represents a coherent unit and is preferably buildable on its declared base.
- The expected review or development overlap justifies maintaining cascading branches.

Prefer ordinary PRs against the trunk when changes are independent. Prefer one PR when splitting would only add ceremony. Avoid stacking on a highly volatile lower layer unless early review of upper layers is valuable.

Distinguish the tools:

- Use stacked PRs to model known dependencies between changes.
- Use a merge queue to validate and serialize already-approved PRs against the latest trunk and earlier queued changes.
- Do not expect either mechanism to resolve semantic or line-level conflicts automatically.

## Maintain the stack model

Interpret the graph from trunk upward:

```text
trunk
└─ bottom-layer
   └─ middle-layer
      └─ top-layer
```

Set each PR base to the branch immediately below it. Expect GitHub's stack map to display the top layer first even though merge order proceeds from the bottom toward the top. Review each PR as the diff for only that layer.

## Choose the workflow

### Create a new stack

1. Start from a clean worktree and an up-to-date trunk.
2. Run `gh stack init <bottom-branch>`.
3. Implement and commit the bottom layer.
4. Run `gh stack add <next-branch>`, then implement and commit that layer.
5. Repeat only for genuinely dependent layers.
6. Inspect with `gh stack view`.
7. With publication authorization, run `gh stack submit` and complete the PR editor according to repository conventions.

### Adopt existing branches

1. Verify that the histories form a linear bottom-to-top chain.
2. Run `gh stack init --base <trunk> <bottom> <middle> <top>` using only the branches that exist.
3. Inspect the inferred order with `gh stack view` before publishing.
4. Run `gh stack submit` only when authorized to push and create or update PRs.

### Link existing pull requests

Use `gh stack link --base <trunk> <bottom-pr> <next-pr> [...]` when the branches and PRs already exist and only native GitHub stack linkage is needed. Supply arguments from bottom to top. Remember that `link` changes remote PR bases and stack metadata but does not create local stack tracking.

### Update the stack

- After editing a lower layer, run `gh stack rebase` to cascade the changed ancestry upward, resolve any conflicts, then run `gh stack push` with authorization.
- Use `gh stack sync` for routine fetch, trunk reconciliation, push, and remote stack-state synchronization. Do not rely on `sync` alone to cascade a lower-layer edit when the trunk did not move; use `rebase` explicitly.
- Expect rebases to rewrite upper-layer commit IDs. Use the extension's force-with-lease behavior; never replace it with an unconditional force push.
- If a rebase stops, inspect the conflict, preserve the intended semantics, stage the resolved files, and run `gh stack rebase --continue`. Use `--abort` to restore the pre-rebase stack.

### Merge the stack

1. Ensure the selected PRs are open, not drafts, approved, and passing required checks.
2. Merge from the bottom through the desired layer by using GitHub's stack UI or `gh stack merge [<stack-or-pr>]`.
3. Prefer the repository's configured merge method. For squash-only repositories, select squash for the stack merge.
4. Explain that a normal stack merge is all-or-nothing up to the selected layer. If the trunk requires a merge queue, GitHub queues the selected layers together, but the queue may land them in separate merge groups.
5. After an individually squash-merged lower PR, run `gh stack rebase` before further local work; it can replay remaining layers onto the squash result.

Do not merge a top PR independently merely to make GitHub wait for its lower dependency. Use the stack merge operation or merge the bottom PR first and then repair and validate the remaining stack.

## Verify and report

- Run `gh stack view` and `git status --short --branch` after local stack operations.
- Inspect the affected PR bases, draft state, checks, and stack order after remote operations.
- State the resulting trunk, bottom-to-top order, PR numbers, and any rewritten or force-with-lease-pushed branches.
- Report conflicts, failed checks, rejected leases, or unmet merge requirements explicitly.

Read [references/commands.md](references/commands.md) for command selection, important side effects, and official references.
