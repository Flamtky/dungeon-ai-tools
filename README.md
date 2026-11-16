# Dungeon AI tools

Agent instructions and skills for working on
[Dungeon-CampusMinden/Dungeon](https://github.com/Dungeon-CampusMinden/Dungeon) with coding
agents such as Claude Code and Codex. The files stay in this repository and are linked to the
places where the agents look for them, so `git pull` here is the whole update.

## Contents

| Path | What it is | Linked to |
| --- | --- | --- |
| `dungeon/AGENTS.md` | Repository rules for agents | `<Dungeon>/AGENTS.md` |
| `dungeon/wizard/AGENTS.md` | Rules for Wizard and Foundation-runtime work | `<Dungeon>/wizard/AGENTS.md` |
| `dungeon/docs/agents/issue-tracker.md` | How agents read issues | `<Dungeon>/docs/agents/issue-tracker.md` |
| `skills/dungeon-play` | Play the game in a background window on Windows: input, state as text, screenshots, video | skill folder |
| `skills/dungeon-play-linux` | The same on a headless Linux machine through Xvfb | skill folder, as `dungeon-play` |
| `skills/dungeon-pr-review` | Review workflow for Dungeon pull requests | skill folder |
| `skills/dungeon-dependabot` | Merge workflow for Dependabot pull requests, for maintainers | skill folder |

## Install

Clone this repository next to your Dungeon checkout and run the script for your platform:

```powershell
./install.ps1 -Dungeon D:\Repos\Dungeon
```

```bash
./install.sh ~/repos/Dungeon
```

- Skills go to `~/.claude/skills` by default. Name other or further folders with `-SkillDirs` /
  `--skill-dirs`, for example `~/.agents/skills` for Codex.
- `-Skills` / `--skills` limits the installation to the skills you name.
- The linked files are added to the checkout's `.git/info/exclude`.
- Whatever already sits where a link belongs moves to `.backup/` in this repository.
- On Windows the file links need Developer Mode or an elevated shell.

Run the script again when a pull brings new files. A new Git worktree of Dungeon does not
contain the linked files; agents read them from the main checkout.

## Requirements

- `dungeon-play` on Windows: PowerShell 7, the JDK Dungeon builds with, `ffmpeg` for video, and
  `~/.local/bin` on `PATH`.
- `dungeon-play` on Linux: `Xvfb`, `xdotool`, `ffmpeg`, the JDK Dungeon builds with, and
  `~/.local/bin` on `PATH`.
- `dungeon-pr-review` and `dungeon-dependabot`: an authenticated `gh`. The review's second
  pass needs T3 Code with a Codex provider and is skipped without them.

## Private additions

Rules and tools that are yours alone stay out of this repository. Keep them in a private
folder with the same layout and pass it as `-Overlay` / `--overlay`:

```text
my-dungeon-tools/
├─ dungeon/AGENTS.local.md                    linked beside AGENTS.md, which tells agents to read it
└─ skills/
   ├─ dungeon-pr-review/STYLE.local.md        linked into the shared skill: your review style
   └─ my-own-skill/                           not a shared skill: linked as a skill of its own
```

Name files that land inside a shared skill `*.local.*`; this repository ignores that pattern.
