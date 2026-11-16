---
name: dungeon-play
description: Play the Dungeon game in a window on this Windows PC without taking the user's focus, mouse or sound: send keys and clicks, take screenshots, record video. Use when an agent needs to test a feature, interaction, menu flow, or gameplay logic in the running game, see a room or level, compare renders before and after a change, or the user asks to play, try out, or record it.
---

# Play Dungeon in a window on the shared desktop

`dungeon-play` (a PowerShell command on PATH; source [`scripts/play.ps1`](scripts/play.ps1)) runs one game per instance. Running it without arguments prints every command. From Git Bash, call `pwsh -NoProfile -File "$HOME/.local/bin/dungeon-play.ps1" <arguments>`. `start` builds through Gradle from the current directory, so run it from the checkout or worktree under test. The build writes only to the ignored `build/` folders, so playing leaves the repository unchanged even where it must not change; a copy of the checkout would build from scratch (about 90 s). In a command sandbox, run every `dungeon-play` command outside it: Gradle writes `~\.gradle`, and the game state lives in `%LOCALAPPDATA%\Temp`. Games keep running after the command returns. Tracking stays local: the game uploads only when built with `DUNGEON_TRACKING_ENDPOINT`, so leave that variable unset. To compare renders before and after a change, follow [`COMPARE.md`](COMPARE.md).

The user and other agents work on this desktop at the same time. A game window opens behind the other windows, without the keyboard focus and muted, and the real mouse passes through it. `dungeon-play` posts its input to that one window and reads screenshots from the game's own frame, so a covered window works like a visible one and two agents' games stay apart. Drive and look at the game through `dungeon-play` alone: computer use, real clicks and screen capture would take the user's mouse and focus. The Gradle daemons and `java.exe` belong to every agent and to the user's IDE: never run `.\gradlew --stop`, and end your own helper processes by PID, never by name (`taskkill /IM java.exe`, `Stop-Process -Name java`).

Games started this way carry an in-game hook, loaded from this skill folder at launch; the repository stays unchanged. The hook answers in text: hero position, inventory, open dialogs with their texts and container contents, visible buttons and texts, the interactables nearby with `#id`, distance and screen pixel, and every change since the last look (items picked up, dialogs opened, hit points, players joining). It also walks, interacts, teleports and sets up state. Text is cheap and exact; every screenshot you `Read` costs image budget. Work from text and read images only when the look itself is under test.

## 1. Start

```powershell
dungeon-play mp runSoulweaver -                # through the main menu to the hero (host, tracking declined, name, character); also for one player
dungeon-play mp runGameOfGames - Bob           # multiplayer: host (default name), Bob joins; more names, more joiners
dungeon-play start runMushRoom                 # the game as it launches (menu or level); any :game:run* task from game/build.gradle
```

Most rooms launch into a main menu (Soulweaver, GameOfGames, SystemRecovery): take `mp <task> -` to play them, and `start` only when the menu itself is under test or the room has none. `start` returns once the game renders; `mp` returns once every hero is in the game, in any menu language and with a room's own host label. On failure both print the log tail and stop what they started. Both take the first free instance from 1 on (other agents use this PC too) and print it, `mp` one per player; put that number in front of every later command. Each instance gets its own network port (42000 + instance). Starts from the same checkout build one after another, then load in parallel. A room offers only as many players as it has characters (GameOfGames: two); rooms without Join Game (SystemRecovery) run the host only.

## 2. Play: state → act → state

```powershell
dungeon-play 1 state                    # hero, inventory, dialogs, UI, nearest #ids, new events
dungeon-play 1 find saphir              # every interactable whose name, or chest content, contains "saphir", however far away
dungeon-play 1 press "Host Game"        # click a button by its text; waits until it is visible and enabled
dungeon-play 1 fill Alice Bob           # replace a text field's content ('' = first field)
dungeon-play 1 goto 17                  # walk with real input until in interaction range of #17
dungeon-play 1 interact 17              # interact; prints the state afterwards
dungeon-play 1 skip                     # Escape through intros and story dialogs; prints every page text; stops at a choice or input
dungeon-play 1 shot C:\tmp\play\01.png  # screenshot + state; --crop x,y,w,h --zoom 3 for small sprites
dungeon-play 1 look C:\tmp\play\cap start:14,12,0.7 cellar:106,48,2   # world without HUD from fixed cameras, one <name>.png per shot
```

Act on what the state shows, never on timing: `goto` and `interact` replace timed key holds and cursor aiming, and `wait '<regex>'` blocks until an event since the last action or a log line matches. Events read `level loaded`, `dialog opened|closed <TYPE> ["title"]`, `inventory <player>: <slots>`, `removed|appeared #<id> <name>`, `hp #<id> <name> <old> -> <new>`, `player joined|left <name>`, `sound <name>` (every sound the game plays; the game is muted, so this is the proof of a sound); menus have none, so check them with `state`.
- **Skip what is not under test.** `skip` advances cutscenes and dialog pages through the player's Escape until no dialog is left, and stops before a dialog that would close with an answer (Yes/No, input, pause menu); `skip '<regex>'` also stops at the first page matching it. The printed page texts keep story hints readable, and they are complete: `state` and `interact` cut a dialog text after 300 characters. `goto` and `interact` refuse while a dialog blocks the hero and name it; `interact` also refuses an out-of-range id.
- **See a room or area** with `look`: `x`/`y` are the camera centre in tiles, zoom `0.35` is the game's close-up, about `1` one area, about `2` an overview. The hero stays where it is, and the view needs a loaded level. Take coordinates from the level file or `state`.
- **`E` aims at the mouse cursor**, not the hero's facing direction; `mouse <x> <y>` places the game's cursor.
- **Raw input when the input itself is under test.** Use `key`, `click`, `mouse`, `drag`, `scroll` and `type`, then check the effect with `state`, `events` or `shot`. Keys carry libGDX names: `E`, `Escape`, `Enter`, `Space`, `Left`, `F4`, `L-Shift`. A second argument holds the key for that many seconds: `key D 0.5` walks right (the hero moves on `W` `A` `S` `D`).
- **Set up instead of playing there** when the path is not under test: `tp`, `give <item>` (registry items and copies of items lying in the level), `hp <n> [id]`, `god`, `kill <id>`.

Done when every behaviour under test has been observed: name what showed it, a state or event line, or a screenshot.

**Level editor** (`run<Room>LevelEditor`) runs server and client in one process. Movement and interaction stay blocked until `F4` leaves editor mode. When the editor closes, it writes the level back to `game/assets/levels/...`. Before starting it, check `git status --short game/assets/levels`: copy every level file that already shows changes, because those are someone's uncommitted work.

**Multiplayer**: `state`, `tp`, `goto`, `interact` and the setup commands on a joined client talk to its server automatically. A dedicated server starts with `dungeon-play server run<Room>Server`; clients joining by hand need `$env:DUNGEON_PLAY_PORT = <host port>` before `start`.

**Logs**: `dungeon-play 1 log` filters out compiler, LWJGL and handshake noise. If the game dies, the next command fails and prints the log tail.

## 3. Record and show

```powershell
dungeon-play 1 record 6 C:\tmp\play\flow.mp4
dungeon-play 1 goto 3
dungeon-play 1 sheet C:\tmp\play\flow.mp4 C:\tmp\play\flow_sheet.png
```

`record` returns at once and films the next seconds; `sheet` waits for the video to finish. `Read` the sheet to check the video. Show the user the relevant screenshots, the sheet and the MP4 as Markdown with absolute paths.

## 4. Stop

```powershell
dungeon-play 1 stop                   # per instance; also ends the hosted server and the clients mp started
git status --short
```

`stop` keeps the log in `%LOCALAPPDATA%\Temp\dungeon-play\<instance>.last\`. A `java.exe` that remains is a shared Gradle daemon; leave it running. Restore every `.level` file the level editor rewrote: from your copy when it had changes before, with `git checkout -- <file>` when it was clean. Rooms may also leave save files in the working directory; check `git status`. Done when `stop` has run for every instance you started and `git status` shows only intended changes.
