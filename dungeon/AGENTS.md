# AGENTS

Keep this file limited to stable, repository-specific rules that are not obvious from the
code. Treat `settings.gradle`, tracked sources, Gradle files, and the linked documentation as
the source of truth for the current project shape and commands. Do not infer active modules
from untracked or ignored directories left in a local checkout.

When `AGENTS.local.md` exists beside this file, read it before starting and follow it as part
of these rules.

`game` is the physical Gradle module for the engine, reusable features, Escape-Room Foundation,
and concrete rooms. These remain separate logical slices even though they share one module.
`wizard` contains authoring, packaging, and the generic Foundation runner. `tracking:core`
contains the Wizard-independent tracking records and JSON codec used by the game and backend.
`tracking:backend` is the self-hosted HTTP and PostgreSQL reference service for receiving and
storing those records. All four are active Gradle modules.

## Engineering boundaries

- Work in the requested module. Change a shared API only when the module genuinely lacks the
  required hook.
- Fix root causes. In multiplayer code, the server is authoritative for shared world state,
  progression, timers, and outcomes; clients submit intent and render synchronized state.
- The project does not require compatibility migrations or deprecation phases by default.
  Change APIs directly.
- Before incrementing any format, application, or protocol version, compare the effective
  feature result with current `master` and inspect the version's `master..HEAD` history. Bump
  only for a compatibility change introduced by the feature branch and only if an earlier
  branch commit has not already made that bump. For incompatible multiplayer wire changes,
  apply this rule to `PROTOCOL_VERSION` in
  `game/src/engine/network/config/NetworkConfig.java`.
- New accessors and mutators omit `get`/`set` prefixes (`value()` and `value(newValue)`). Prefer
  `Optional` over nullable public return values.
- Use `DungeonLogger` for implementation logging and plain `println` for ad-hoc diagnostics.
- Register dialog types in every runtime that creates them. Client-side translation preserves
  the complete typed `DialogContext`; a server-forced close remains authoritative even when the
  dialog cannot be closed by the user.

## Verification

- Keep new tests minimal: a few unit tests for the central behavior a change introduces, the rule
  or contract that would otherwise break unnoticed. Each test covers what no other test covers.
  Leave further inputs for the same branch, edge cases the change does not create, and bulk checks
  of content such as texts to an explicit request. Update existing tests when behavior or
  contracts change.
- For implementation work, record the pre-existing worktree state and keep every change
  produced by `spotlessApply`. If it touches files outside the manual edit scope, inspect their
  diff and `master..HEAD` history: an earlier feature-branch commit may have introduced the
  unformatted content. Do not revert Spotless output, because `master` is the formatted
  baseline; formatter-only changes are the exception to the normal module boundary.
- Do not modify reviewed source unless fixes are explicitly requested. Use `spotlessCheck`, not
  `spotlessApply`, during a review.
- Run Spotless and full verification natively on the OS that holds the checkout. For a Windows
  checkout that means Windows-native, because WSL path translation can make Spotless reject
  valid files.
- For `wizard/spec-generator`, run `npm run fmt` and `npm run lint` there instead of Spotless, and keep
  the formatter output.
- Do not list successful local Gradle tasks or tests in PR text or completion summaries unless
  the user asks for verification details. Always report failures or blockers.

## Assets

- Assets from `game/assets` form the shared base namespace for `game` and `wizard`. Check for
  collisions before adding or renaming assets.
- The generated internal asset list excludes `.md` files. Use a shippable extension or change
  the pipeline when Markdown content must be available at runtime.

## Wizard

- Before Wizard or shared Foundation-runtime work, read `wizard/AGENTS.md`.

## GitHub

- Never rename or delete a remote branch with an open PR merely to improve terminology. GitHub
  closes the PR when its head branch is renamed, and a native stack can retain the removed
  branch. Preserve the branch and PR; change only user-facing names. If branch replacement is
  genuinely required, obtain explicit approval first and rebuild the stack deliberately.
- After changing native stack linkage, verify the remote state rather than trusting the local
  branch graph: remove only local tracking with `gh stack unstack --local`, import the stack
  again through `gh stack checkout <pr>`, then confirm `gh stack view` and every PR base.
- Before drafting or editing a PR, read `CONTRIBUTING.md` and sample the user's recent comparable
  PRs. Match their language, structure, and level of detail. Describe the resulting target state
  established by the PR; include prior-state context only when it clarifies that outcome, and
  omit non-changes or exclusions. Never retain generated tool attribution or feedback footers.
- GitHub removes the workflow runs of older PRs, so such a PR shows no checks. That is expected.
  Close and reopen the PR to run the pipeline again, but only once it is about to be merged:
  while a review is pending, wait for the approval first.

## Reviews

- For PR reviews, first read the linked issue, its requirements and discussion, and the existing
  PR comments. Create a dedicated review worktree from the current PR head and leave the primary
  checkout untouched; do not stash, pull, or switch branches in it for the review.
- `AGENTS.md`, `AGENTS.local.md`, and `wizard/AGENTS.md` are local ignored files and are
  therefore absent from a new worktree. Copy them to the same relative locations in the review
  worktree before starting; keep them ignored and uncommitted.
- Security is a low product priority for this small game. Review it as a calculated risk: do not
  block on speculative hardening, but report concrete, plausible violations of a basic security
  floor, such as unauthorized file access, arbitrary code execution, credential exposure, or a
  client bypass of server authority. State likelihood and impact.
