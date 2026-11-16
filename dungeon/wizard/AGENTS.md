# Wizard AGENTS

These rules apply to Wizard authoring, the generic runner, and shared Foundation runtime work.
Wizard is a Gradle module; Foundation is a logical slice under
`game/src/escaperoom/foundation`, not a separate module.
The current implementation and its `wizard/doc/README.md`, DEER schema,
`runner-project-format.md`, and `runner-runtime-contract.md` are authoritative; keep code,
contracts, examples, and documentation consistent.

## Product boundary

- The Wizard is a throwaway product within the Dungeon project and is close to complete. It only
  needs to work and look acceptable in typical cases. Keep changes minimal; do not invest in new
  tests, tooling, or edge-case hardening unless explicitly requested.
- The target users are non-technical teachers. The authoring UI must hide JSON, Java, Gradle,
  Petri-net, and runtime-token concepts.
- Preserve this flow:

  ```text
  private Wizard draft
  -> finalized DEER project (deer.json + optional assets/custom/)
  -> project-specific WizardRoom.jar
  -> Host/Join menu
  -> deterministic in-memory Foundation room
  -> Dungeon multiplayer
  ```

- `deer.json` has a mandatory stable `seed`. Custom files are content-addressed under
  `assets/custom/`; bundled game assets are referenced by their internal paths.
- The generic runner validates the complete DEER project read-only and derives the room in
  memory. It does not generate Java code, a room module, `.level` files, build scripts, or a
  Room ZIP.
- The player flow distributes the exact same complete, project-specific `WizardRoom.jar` to the
  host and every joining client. Do not introduce a separately distributed assets-only folder
  or server artifact.
- Solutions and unreleased content are locally inspectable by every JAR recipient. Only the
  host evaluates answers and decides shared progression, timers, and outcomes; clients derive
  the same room and render authoritative synchronized state.
- Foundation terminal cutscenes remain non-closeable by the user. Their server-driven close
  still has to close the client dialog during terminal shutdown.

## Verification

- Before committing authoring UI or host changes, verify the changed behavior in a real browser
  against the locally running authoring host at `http://127.0.0.1:27777/`; build gates alone are
  not sufficient. Exercise the flow end-to-end (for example, multi-tab behavior needs two tabs)
  and clean up any test drafts afterwards.
