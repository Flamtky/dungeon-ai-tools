---
name: dungeon-play
description: Play the Dungeon game on Linux through a virtual display, out of the user's sight and hearing, or in a real window on the user's desktop when they ask for one: send keys and clicks, take screenshots, record video, show it live on request. Use when an agent needs to test a feature, interaction, menu flow, or gameplay logic in the running game, see a room or level, compare renders before and after a change, or the user asks to play, try out, or record it.
---

# Play Dungeon on a virtual display

`dungeon-play` (on PATH; source [`scripts/play.sh`](scripts/play.sh)) runs one game per Xvfb display. Running it without arguments prints every command. `start` builds through Gradle from the current directory, so run it from the checkout or worktree under test. The build writes only to the ignored `build/` folders, so playing leaves the repository unchanged even where it must not change; a copy of the checkout would build from scratch (about 90 s). In a command sandbox, run every `dungeon-play` command outside it: Xvfb needs `/tmp/.X11-unix`, Gradle writes `~/.gradle`, and a sandbox's own process view reports the game as not running. Games keep running after the command returns. A game is silent: it opens no audio device unless started with `DUNGEON_PLAY_SOUND=1`. Tracking stays local: the game uploads only when built with `DUNGEON_TRACKING_ENDPOINT`, so leave that variable unset. Never run `./gradlew --stop`: the Gradle daemons are shared by every agent on this machine. All agents also share one user, so end your own helper processes by PID, never by name pattern (`pkill -f`). To compare renders before and after a change, follow [`COMPARE.md`](COMPARE.md).

Games started this way carry an in-game hook, loaded from the `dungeon-play` folder beside this skill's own at launch; the repository stays unchanged. The hook answers in text: hero position, inventory, open dialogs with their texts and container contents, visible buttons and texts, the interactables nearby with `#id`, distance and screen pixel, and every change since the last look (items picked up, dialogs opened, hit points, players joining). It also walks, interacts, teleports and sets up state. Text is cheap and exact; every screenshot you `Read` costs image budget. Work from text and read images only when the look itself is under test.

The hook draws the game world only while `shot`, `look` or `record` captures it, so the game runs at full speed (rendering under software GL would drop it to 5 to 10 FPS). An open `view` keeps the world drawn. Menus, dialogs and the HUD always draw.

## 1. Start

```bash
dungeon-play mp runGameOfGames -               # rooms with a main menu: straight to the hero (host, tracking declined, name, character)
dungeon-play mp runGameOfGames - Bob           # multiplayer: host (default name), Bob joins; more names, more joiners
dungeon-play start runMushRoom                 # the game as it launches (menu or level); any :game:run* task from game/build.gradle
```

`start` returns once the game renders; `mp` returns once every hero is in the game, in any menu language and with a room's own host label. On failure both print the log tail and stop what they started. Both take the first free display from :100 on (other agents use this machine too) and print it, `mp` one per player; pass `DUNGEON_PLAY_DISPLAY=<display>` to every later command. A display belongs to the checkout that started its game: every command from another checkout, `stop` included, fails and names the owner, and `dungeon-play --force <command>` runs it anyway. Each display gets its own network port (50000 + display number). Starts from the same checkout build one after another, then load in parallel. A room offers only as many players as it has characters (GameOfGames: two); rooms without Join Game (SystemRecovery) run the host only.

**In a real window on the user's desktop**, when the user asks to see the game itself in a window: put `DUNGEON_PLAY_DESKTOP=1` in front of `start` or `mp`.

```bash
DUNGEON_PLAY_DESKTOP=1 dungeon-play mp runGameOfGames -
```

The game opens as an ordinary window titled `… [dungeon-play :100, driven by an agent]`, placed and sized by the window manager; it may take the keyboard focus as it appears. The number is its `DUNGEON_PLAY_DISPLAY` for every later command, as with a virtual display. Input goes into the game process: it needs no focus and leaves the user's mouse alone, though the user's own mouse and keys reach the window too. The world always draws.

- The start needs the desktop session's `WAYLAND_DISPLAY` or `DISPLAY`, and `XDG_RUNTIME_DIR`. A shell without them (Codex) sets them first, e.g. `XDG_RUNTIME_DIR=/run/user/$(id -u) WAYLAND_DISPLAY=wayland-1 DISPLAY=:0`.
- `key` takes libGDX key names (`E`, `Escape`, `Enter`, `Space`, `Left`, `F4`, `L-Shift`, `Page_Up`; case does not matter, `_` stands for a space) instead of X keysyms, one key per call.
- The window manager resizes the window when others open or close, so take pixel coordinates from a fresh `state` or `shot` right before a `click` or `drag`. On an output scaled other than 1, screenshot pixels and click coordinates may differ (not verified).
- `view` refuses, since the game already is a window. `shot` and `record` paths must not contain `|`, `?` or control characters.
- When the user closes the window, run `stop` for its display.

## 2. Play: state → act → state

```bash
dungeon-play state                    # hero, inventory, dialogs, UI, nearest #ids, new events
dungeon-play find saphir              # every interactable whose name, or chest content, contains "saphir", however far away
dungeon-play press "Host Game"        # click a button by its text; waits until it is visible and enabled
dungeon-play fill Alice Bob           # replace a text field's content ('' = first field)
dungeon-play goto '#17'               # walk with real input until in interaction range
dungeon-play interact '#17'           # interact; prints the state afterwards
dungeon-play skip                     # Escape through intros and story dialogs; prints every page text; stops at a choice or input
dungeon-play shot /tmp/play/01.png    # screenshot without cursor + state; --crop x,y,w,h --zoom 3 for small sprites
dungeon-play look /tmp/play/cap start:14,12,0.7 cellar:106,48,2   # world without HUD from fixed cameras, one <name>.png per shot
```

Act on what the state shows, never on timing: `goto` and `interact` replace timed key holds and cursor aiming, and `wait '<regex>'` blocks until an event since the last action or a log line matches. Events read `level loaded`, `dialog opened|closed <TYPE> ["title"]`, `inventory <player>: <slots>`, `removed|appeared #<id> <name>`, `hp #<id> <name> <old> -> <new>`, `player joined|left <name>`, `sound <name>` (every sound the game asks to play: the proof of a sound); menus have none, so check them with `state`.
- **Skip what is not under test.** `skip` advances cutscenes and dialog pages through the player's Escape until no dialog is left, and stops before a dialog that would close with an answer (Yes/No, input, pause menu); `skip '<regex>'` also stops at the first page matching it. The printed page texts keep story hints readable. `goto` and `interact` refuse while a dialog blocks the hero and name it; `interact` also refuses an out-of-range `#id`.
- **See a room or area** with `look`: `x`/`y` are the camera centre in tiles, zoom `0.35` is the game's close-up, about `1` one area, about `2` an overview. The hero stays where it is, and the view needs a loaded level. Take coordinates from the level file or `state`.
- **`E` aims at the mouse cursor**, not the hero's facing direction.
- **Raw input when the input itself is under test.** Use `key`, `click`, `mouse`, `drag`, `scroll` and `type`, then check the effect with `state`, `events` or `shot`.
- **Set up instead of playing there** when the path is not under test: `tp`, `give <item>` (registry items and copies of items lying in the level), `hp <n> ['#id']`, `god`, `kill '#id'`.

Done when every behaviour under test has been observed: name what showed it, a state or event line, or a screenshot.

**Level editor** (`run<Room>LevelEditor`) runs server and client in one process. Movement and interaction stay blocked until `F4` leaves editor mode. When the editor closes, it writes the level back to `game/assets/levels/...`.

**Multiplayer**: `state`, `tp`, `goto`, `interact` and the setup commands on a joined client talk to its server automatically. A dedicated server starts with `dungeon-play server run<Room>Server`; clients joining by hand need `DUNGEON_PLAY_PORT=<host port>` on `start`.

**Logs**: `dungeon-play log` filters out ALSA, LWJGL and handshake noise. If the game dies, the next command fails and prints the log tail; its display number stays taken until `stop` has run for it.

## 3. Record and show

```bash
dungeon-play record 6 /tmp/play/flow.mp4 &
dungeon-play goto '#3'; wait
dungeon-play sheet /tmp/play/flow.mp4 /tmp/play/flow_sheet.png
```

The game runs at render speed while recording. `Read` the sheet to check the video. Show the user the relevant screenshots, the sheet and the MP4 as Markdown with absolute paths.

## 4. Show it live

```bash
dungeon-play view                     # a window on the user's desktop shows the display live
dungeon-play view off
```

Use `view` when the user asks to watch. The window shows the display as it is, cursor included; input stays yours. The view ends when the user closes the window, or with `view off` or `stop`.

## 5. Stop

```bash
dungeon-play stop                     # per display; also ends the hosted server and the clients mp started
git status --short
```

`stop` keeps the log in `/tmp/dungeon-play/<display>.last/`. Restore any `.level` file the level editor rewrote with `git checkout -- <file>`. Rooms may also leave save files in the working directory; check `git status`. Done when `stop` has run for every display you started and `git status` shows only intended changes.
