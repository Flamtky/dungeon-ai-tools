#!/usr/bin/env bash
# Drive a Dungeon game on a virtual X display. Installed as `dungeon-play` on PATH.
# One game per display. `start`, `server` and `mp` take the first free display from :100 on unless
# DUNGEON_PLAY_DISPLAY is set and print it; every later command needs DUNGEON_PLAY_DISPLAY=<that display>.
# The game's network port is 50000 + display number unless DUNGEON_PLAY_PORT is set on `start`.
# Run `start` from the checkout or worktree under test. State and logs: /tmp/dungeon-play/<display>/ (kept as .last after stop).
#
# Lifecycle
#   start <gradle-task> [gradle args]   Xvfb + game; returns once the game renders
#   mp <gradle-task> <host-name|-> <name>...   host on this display, one joined client per name on the
#                                       next displays; returns once every hero is in the game
#   server <gradle-task> [gradle args]  dedicated server (e.g. run<Room>Server); returns once it runs
#   stop                                close the game and every process it spawned (incl. hosted
#                                       server and the clients `mp` started)
# Look
#   shot <file.png> [--crop x,y,w,h] [--zoom n]   screenshot (no cursor) + the text state
#   look <dir> <name:x,y,zoom>...       world without HUD from fixed cameras into <dir>/<name>.png: x,y = camera
#                                       centre in tiles, zoom 0.35 close-up, about 1 one area, about 2 overview
#   compare <before> <after> <out>      diff two directories of PNGs: changed pixels, side-by-side and marked images
#   state                               hero, inventory, dialogs (+ their texts, containers), UI texts and
#                                       buttons, players, nearest interactables (+ screen px), new events
#   find <name-part>                    every interactable whose name or held item contains <name-part>, with #id
#   events [n]                          what changed: dialogs, inventories, players, items, hit points, sounds
#   log [lines]                         game log without ALSA/LWJGL/stack-frame noise
#   wait <regex> [seconds]              block until an event since the last action, or the game log, matches
#                                       (default 30 s)
#   record <seconds> <file.mp4>         video (blocks; run with & to act meanwhile)
#   sheet <file.mp4> <file.png> [fps]   contact sheet of a video
# Act (game coordinates are tiles, screen coordinates are window pixels = screenshot pixels)
#   goto <x> <y> | goto #<id>           walk there along a path (real input); #id stops in interaction range
#   interact #<id> | interact <x> <y>   interact with an entity or point; an out-of-range #id is an error
#   skip [regex]                        Escape on the topmost dialog until none takes it (cutscene and
#                                       script pages advance); stops at a choice, input or a page
#                                       matching regex; prints the skipped page texts
#   tp <x> <y> | tp #<id>               teleport the hero (server-side debug move)
#   press <text>                        click the visible button/field/text containing <text>
#   key <keysym> [hold-seconds]         press a key (default hold 0.08 s)
#   type <text>                         type into the focused field
#   fill <field-text> <new-text>        replace the content of the field showing <field-text>
#   click <x> <y> [button]  mouse <x> <y>  drag <x1> <y1> <x2> <y2>  scroll <up|down> [n]
# Set up (server-side debug changes)
#   give <item> [n]                     add n of the item whose id or name contains <item> to the hero
#   hp <n> [#id]                        set hit points of the hero or an entity
#   god [off]                           hero cannot die (hit points stay at least 1)
#   kill #<id>                          kill an entity (death handling runs) or remove it
set -euo pipefail

SKILL="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
# The in-game hook and the image comparison are the Windows skill's: one source for both platforms.
SHARED="$(cd "$SKILL/../dungeon-play" && pwd)"
# The JDK Dungeon builds with: JAVA_HOME, else the Temurin 25 package, else the one whose javac is on PATH.
if [[ -n "${JAVA_HOME:-}" ]]; then JDK="$JAVA_HOME"
elif [[ -d /usr/lib/jvm/temurin-25-jdk-amd64 ]]; then JDK=/usr/lib/jvm/temurin-25-jdk-amd64
else JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac || echo javac)")")")"; fi
# Points every per-display variable at display :$1.
use_display() {
  DISP=":$1"; STATE="/tmp/dungeon-play/$DISP"; export DISPLAY="$DISP"
  SIZE="$(cat "$STATE/size" 2>/dev/null || echo "${DUNGEON_PLAY_SIZE:-1280x750}")"
  PORT="${DUNGEON_PLAY_PORT:-$((50000 + $1))}"
}
DISP="${DUNGEON_PLAY_DISPLAY:-:100}"; use_display "${DISP#:}"
NOISE='ALSA lib|^\s+at |^\s*\.\.\. [0-9]+ more|LWJGL|Unsafe|sun\.misc|Generated internal assets|NOTE: Picked up JDK_JAVA_OPTIONS|connection preamble|Failed connection handshake|^\[Controllers\]'

die() { echo "$*" >&2; exit 1; }
# Prints why display :$1 cannot take a new game, or nothing when it is free; checks port $2 if given.
taken() {
  # Xvfb takes over a display whose lock names a dead process (left by a killed X server).
  local x; x="$(tr -d ' ' 2>/dev/null <"/tmp/.X$1-lock" || true)"
  if [[ -n "$x" && -d "/proc/$x" ]] || [[ -z "$x" && -e "/tmp/.X11-unix/X$1" ]]; then echo "display :$1 is taken"
  elif [[ -n "${2:-}" && -n "$(ss -Hltn "sport = :$2" 2>/dev/null)" ]]; then echo "port $2 of display :$1 is in use"; fi
}
alive() { [[ -f "$STATE/$1.pid" ]] && kill -0 "$(cat "$STATE/$1.pid")" 2>/dev/null; }
win() { [[ -f "$STATE/window" ]] || die "no game window on $DISP (run: dungeon-play start <task>)"; cat "$STATE/window"; }
cleanlog() { grep -avE "$NOISE" "$STATE/game.log" 2>/dev/null || true; }

# Fails loudly when the game died, instead of acting on a dead window.
need_game() {
  alive game && return 0
  echo "game on $DISP is not running (crashed or closed). Last log lines:" >&2
  cleanlog | tail -15 >&2
  exit 1
}
focus() { need_game; xdotool windowfocus --sync "$(win)"; }

chan_ok() { [[ -f "$1/pid" ]] && kill -0 "$(cat "$1/pid")" 2>/dev/null; }

# req <channel-dir> <timeout-s> <command...>: one request to the in-game hook, prints the answer.
req() {
  local dir="$1" timeout="$2"; shift 2
  chan_ok "$dir" || { echo "game hook not available on $DISP (game not started through dungeon-play?)" >&2; return 1; }
  local id="$$.$RANDOM$RANDOM"
  printf '%s %s\n' "$(( $(date +%s%3N) + timeout * 1000 ))" "$*" >"$dir/new.$id" && mv "$dir/new.$id" "$dir/req.$id"
  local n=$((timeout * 20))
  while ((n-- > 0)); do
    if [[ -f "$dir/res.$id" ]]; then cat "$dir/res.$id"; rm -f "$dir/res.$id"; return 0; fi
    sleep 0.05
  done
  rm -f "$dir/req.$id"
  echo "no answer from the game within ${timeout}s (frozen or still loading?); the command was not run" >&2
  return 1
}

# The process with world authority: the game itself (single process) or the hosted/dedicated server.
server_chan() {
  if [[ -n "${DUNGEON_PLAY_SERVER:-}" ]]; then echo "/tmp/dungeon-play/$DUNGEON_PLAY_SERVER/server"; return; fi
  if chan_ok "$STATE/server"; then echo "$STATE/server"; return; fi
  # A joined client: the server hook listening on the port this client uses.
  local d
  for d in /tmp/dungeon-play/*/server; do
    if chan_ok "$d" && [[ "$(cat "$d/port" 2>/dev/null)" == "$CTX_PORT" ]]; then echo "$d"; return; fi
  done
  die "no dungeon-play server hook on port $CTX_PORT; set DUNGEON_PLAY_SERVER=<display of the host>"
}

# Sets CTX_AUTH, CTX_PLAYER, CTX_PORT, CTX_CAM from the game process.
load_ctx() {
  local c; c="$(req "$STATE/game" 10 ctx)"
  CTX_AUTH="$(sed -n 's/.*authority=\([01]\).*/\1/p' <<<"$c")"
  CTX_PLAYER="$(sed -n 's/.*player=\([^ ]*\).*/\1/p' <<<"$c")"
  CTX_CAM="$(sed -n 's/.*cam=\([^ ]*\).*/\1/p' <<<"$c")"
  CTX_PORT="$(sed -n 's/.*port=\([0-9]*\).*/\1/p' <<<"$c")"
}
auth_chan() { if [[ "$CTX_AUTH" == 1 ]]; then echo "$STATE/game"; else server_chan; fi; }

print_state() {  # print_state [match]: full state, or only the interactables whose name contains match
  load_ctx
  [[ -n "${1:-}" ]] || req "$STATE/game" 10 view
  [[ "$CTX_AUTH" == 1 || "$CTX_PLAYER" != - ]] || return 0
  local args=""; [[ -n "$CTX_CAM" ]] && args="cam=$CTX_CAM"
  local chan; chan="$(auth_chan 2>/dev/null)" || { echo "(no server hook: players/interactables unknown)"; return 0; }
  req "$chan" 10 world $args "player=$CTX_PLAYER" ${1:+"match=$1"}
  [[ -z "${1:-}" ]] || return 0
  local seen new; seen="$(cat "$STATE/events.seen" 2>/dev/null || echo 0)"
  new="$(all_events | awk -v s="$seen" '$1 > s')"
  [[ -n "$new" ]] || return 0
  (( $(wc -l <<<"$new") <= 15 )) || echo "($(( $(wc -l <<<"$new") - 15 )) earlier events: dungeon-play events)"
  tail -15 <<<"$new" | show_events
  tail -1 <<<"$new" | cut -d' ' -f1 >"$STATE/events.seen"
}

# Asks the authoritative process; for player commands on behalf of this display's hero.
auth_req() { load_ctx; local r; r="$(req "$(auth_chan)" 10 "$@" "player=$CTX_PLAYER")"; echo "$r"; [[ "$r" != error* ]]; }

# Fails with the open dialog's name when one keeps the hero from moving and interacting.
unblocked() { local r; r="$(auth_req blocked)" || die "$r"; }

# Clicks the widget showing <text> once it is visible and enabled (menus load asynchronously).
click_widget() {
  local xy n=60
  while :; do
    xy="$(req "$STATE/game" 10 widget "$1")"
    [[ "$xy" != error* ]] && break
    ((n-- > 0)) || die "$xy (waited 15 s)"
    need_game; sleep 0.25
  done
  xdotool mousemove ${xy} click 1
  echo "$xy"
}

render() { if chan_ok "$STATE/game"; then req "$STATE/game" 5 render "$1" >/dev/null || true; fi; }
# Scene rendering for a capture; off again on exit unless a recording still runs or it is always on.
capture_render() {
  render on
  [[ "${DUNGEON_PLAY_RENDER:-}" == always ]] || trap '[[ -e "$STATE/recording" ]] || render off' EXIT
}

# Event lines "<epoch-ms> <text>" of this game and, for a joined client, of its server; sorted.
all_events() {
  local files=("$STATE/game/events")
  if [[ "${CTX_AUTH:-1}" != 1 ]]; then local s; s="$(server_chan 2>/dev/null)" && files+=("$s/events"); fi
  cat "${files[@]}" 2>/dev/null | sort -n -s -k1,1 || true
}
show_events() { awk -v now="$(date +%s%3N)" '{ printf "event %5.1fs ago: %s\n", (now - $1) / 1000, substr($0, index($0, " ") + 1) }'; }

# resolve <#id | x y>: sets X, Y, RANGE and, for an entity, the hero's DIST (world coordinates).
resolve() {
  local r
  if [[ "$1" == \#* ]]; then load_ctx; r="$(req "$(auth_chan)" 10 find "${1#\#}" "player=$CTX_PLAYER")"
  else r="${1:?x} ${2:?y} 0"; fi
  [[ "$r" == error* ]] && die "$r"
  read -r X Y RANGE DIST <<<"$r"
}

build_agent() {
  local jar="$SHARED/hook/build/agent.jar" src="$SHARED/hook/agent/dungeonplay/Premain.java"
  if [[ ! -f "$jar" || "$src" -nt "$jar" ]]; then
    rm -rf "$SHARED/hook/build" && mkdir -p "$SHARED/hook/build/classes"
    "$JDK/bin/javac" -d "$SHARED/hook/build/classes" "$src"
    printf 'Premain-Class: dungeonplay.Premain\n' >"$SHARED/hook/build/manifest"
    "$JDK/bin/jar" cfm "$jar" "$SHARED/hook/build/manifest" -C "$SHARED/hook/build/classes" .
  fi
  echo "$jar"
}

launch() {  # launch <window|server> <task> [gradle args]
  local mode="$1" task="${2:?gradle task, e.g. runBasicStarter}"; shift 2
  [[ -x ./gradlew ]] || die "run from the Dungeon checkout or worktree"
  mkdir -p /tmp/dungeon-play
  # The lock is held for the whole start. A set DUNGEON_PLAY_PORT is a host's port, so it stays unchecked.
  local d="${DISP#:}" why own
  while :; do
    exec 9>"/tmp/dungeon-play/$d.lock"
    own=""; [[ -n "${DUNGEON_PLAY_PORT:-}" ]] || own=$((50000 + d))
    if flock -n 9; then why="$(taken "$d" "$own")"; else why="another dungeon-play start is running on :$d"; fi
    [[ -z "$why" ]] && break
    [[ -n "${DUNGEON_PLAY_DISPLAY:-}" ]] && die "$why; stop it or set DUNGEON_PLAY_DISPLAY to another display"
    d=$((d + 1))
  done
  use_display "$d"
  # A failed start cleans up after itself; the success path clears this.
  trap 'DUNGEON_PLAY_DISPLAY="$DISP" "$0" stop >/dev/null 2>&1 || true' EXIT
  local jar; jar="$(build_agent)"
  rm -rf "$STATE"; mkdir -p "$STATE/pids"; echo "$SIZE" >"$STATE/size"
  # Xvfb and Gradle run in their own session: agent shells that end a command's process group
  # when the command returns (Codex) would otherwise end the game with `start`.
  setsid Xvfb "$DISP" -screen 0 "${SIZE}x24" -nolisten tcp >"$STATE/xvfb.log" 2>&1 &
  echo $! >"$STATE/xvfb.pid"
  for _ in $(seq 50); do xdotool getdisplaygeometry >/dev/null 2>&1 && break; sleep 0.1; done
  # Gradle builds in one checkout race on game/build, so they take turns; the lock is released
  # once the game JVM runs, and the games themselves start in parallel.
  exec 8>"/tmp/dungeon-play/build.$(pwd -P | md5sum | cut -c1-16).lock"
  flock -w 600 8 || die "another dungeon-play build in this checkout has not finished within 600 s"
  DUNGEON_PLAY_STATE="$STATE" DUNGEON_PLAY_AGENT_JAR="$jar" DUNGEON_PLAY_HOOK_SRC="$SHARED/hook/src" DUNGEON_PLAY_PORT="$PORT" \
    setsid ./gradlew ":game:$task" --init-script "$SHARED/hook/init.gradle" --console=plain -q "$@" >"$STATE/game.log" 2>&1 8>&- &
  echo $! >"$STATE/game.pid"
  local chan="$STATE/game"; [[ "$mode" == server ]] && chan="$STATE/server"
  for _ in $(seq 600); do
    [[ -n "$(ls -A "$STATE/pids")" ]] && exec 8>&-
    if chan_ok "$chan" && req "$chan" 5 ping >/dev/null 2>&1; then break; fi
    alive game || { echo "game exited during start:" >&2; cleanlog | tail -20 >&2; exit 1; }
    sleep 0.5
  done
  chan_ok "$chan" || die "game did not come up within 300 s; log: $STATE.last/game.log"
  if [[ "$mode" == window ]]; then
    local w=""
    for _ in $(seq 40); do w=$(xdotool search --onlyvisible --name . 2>/dev/null | head -1 || true); [[ -n "$w" ]] && break; sleep 0.25; done
    [[ -n "$w" ]] || die "game runs but no window appeared; log: $STATE.last/game.log"
    echo "$w" >"$STATE/window"
    xdotool windowmove "$w" 0 0 windowsize "$w" "${SIZE%x*}" "${SIZE#*x}"
    echo "window $w \"$(xdotool getwindowname "$w")\" on DUNGEON_PLAY_DISPLAY=$DISP, port $PORT; log: $STATE/game.log"
  else
    echo "server running on DUNGEON_PLAY_DISPLAY=$DISP, port $PORT; log: $STATE/game.log"
  fi
  trap - EXIT
}

cmd="${1:-}"; shift || true
case "$cmd" in
  start|server|mp|sheet|compare|"") ;;
  *) [[ -n "${DUNGEON_PLAY_DISPLAY:-}" ]] || die "set DUNGEON_PLAY_DISPLAY to the display that start or mp printed" ;;
esac
# wait also matches what the latest action caused, even when that action has already returned.
case "$cmd" in
  key|type|click|drag|scroll|press|fill|goto|interact|skip|tp|give|hp|kill) { date +%s%3N >"$STATE/acted"; } 2>/dev/null || true ;;
esac
case "$cmd" in
  start)  launch window "$@" ;;
  server) launch server "$@" ;;
  compare) "$JDK/bin/java" -Djava.awt.headless=true "$SHARED/scripts/Compare.java" "${1:?before directory}" "${2:?after directory}" "${3:?output directory}" ;;
  key)
    focus
    xdotool keydown "$1"; sleep "${2:-0.08}"; xdotool keyup "$1" ;;
  type)   focus; xdotool type --delay 40 "$1" ;;
  click)  focus; xdotool mousemove "$1" "$2" click "${3:-1}" ;;
  mouse)  focus; xdotool mousemove "$1" "$2" ;;
  drag)
    focus
    xdotool mousemove "$1" "$2" mousedown 1; sleep 0.15
    xdotool mousemove "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))"; sleep 0.1
    xdotool mousemove "$3" "$4"; sleep 0.15; xdotool mouseup 1 ;;
  scroll) focus; xdotool click --repeat "${2:-1}" --delay 50 "$([[ "$1" == up ]] && echo 4 || echo 5)" ;;
  press)
    focus
    xy="$(click_widget "$*")"
    echo "clicked $xy" ;;
  fill)
    focus
    xy="$(click_widget "${1?text of the field (empty: first field)}")"
    xdotool key End
    xdotool key --repeat $(( ${#1} + 30 )) --delay 5 BackSpace
    xdotool type --delay 40 "${2:?new text}"
    echo "filled $xy" ;;
  shot)
    need_game
    out="${1:?file.png}"; shift; crop=""; zoom=""
    while (($#)); do case "$1" in --crop) crop="$2"; shift 2 ;; --zoom) zoom="$2"; shift 2 ;; *) die "unknown option $1" ;; esac; done
    vf=""
    if [[ -n "$crop" ]]; then IFS=, read -r cx cy cw ch <<<"$crop"; vf="crop=$cw:$ch:$cx:$cy"; fi
    if [[ -n "$zoom" ]]; then vf="${vf:+$vf,}scale=iw*$zoom:ih*$zoom:flags=neighbor"; fi
    # Hook round trips = whole frames rendered after the last input (scene back on, hover, tooltips).
    capture_render
    if chan_ok "$STATE/game"; then for _ in 1 2 3; do req "$STATE/game" 5 ping >/dev/null; done; fi
    ffmpeg -loglevel error -y -f x11grab -draw_mouse 0 -video_size "$SIZE" -i "$DISP" -frames:v 1 ${vf:+-vf "$vf"} "$out"
    if chan_ok "$STATE/game"; then print_state; fi ;;
  look)
    need_game
    out="${1:?output directory}"; shift
    (($#)) || die "name at least one shot: name:x,y,zoom"
    for s in "$@"; do [[ "$s" =~ ^[A-Za-z0-9_-]+:-?[0-9.]+,-?[0-9.]+,[0-9.]+$ ]] || die "shot '$s' is not name:x,y,zoom"; done
    mkdir -p "$out"
    capture_render
    for _ in 1 2 3; do req "$STATE/game" 5 ping >/dev/null; done
    r="$(req "$STATE/game" 60 look "$(cd "$out" && pwd)" "$@")" || die "$r"
    [[ "$r" == error* ]] && die "$r"
    echo "$r" ;;
  state)  need_game; print_state ;;
  find)   need_game; print_state "${1:?part of an entity name}" ;;
  events) need_game; load_ctx; all_events | tail -n "${1:-30}" | show_events ;;
  log)    cleanlog | tail -n "${1:-40}" ;;
  wait)
    need_game; load_ctx
    re="${1:?regex}"; n=$(( ${2:-30} * 4 )); since="$(cat "$STATE/acted" 2>/dev/null || date +%s%3N)"
    rm -f "$STATE/acted"
    while ((n-- > 0)); do
      all_events | awk -v s="$since" '$1 >= s' | show_events | grep -aE -m1 "$re" && exit 0
      cleanlog | grep -aE -m1 "$re" && exit 0
      need_game; sleep 0.25
    done
    die "no new event and no log line matched /$re/ within ${2:-30}s" ;;
  goto)
    need_game
    unblocked
    resolve "$@"
    [[ "$1" == \#* ]] || RANGE=0
    req "$STATE/game" 100 goto "$X" "$Y" "$RANGE" ;;
  interact)
    need_game
    unblocked
    resolve "$@"
    if [[ -n "$DIST" ]] && awk -v d="$DIST" -v r="$RANGE" 'BEGIN { exit !(d + 0 > r + 0) }'; then
      die "error: $1 is out of range (distance $(printf %.1f "$DIST"), range $(printf %.1f "$RANGE")); run goto first"
    fi
    req "$STATE/game" 10 interact "$X" "$Y" ;;
  menu)   need_game; req "$STATE/game" 10 menu ;;
  skip)
    need_game
    req "$STATE/game" 130 skip "${1:-}" ;;
  tp)
    need_game
    resolve "$@"
    auth_req tp "$X" "$Y" ;;
  give) need_game; auth_req give "${1:?item id or name part}" "${2:-1}" ;;
  hp)
    need_game
    if [[ -n "${2:-}" ]]; then auth_req hp "${1:?hit points}" "${2#\#}"; else auth_req hp "${1:?hit points}"; fi ;;
  god)  need_game; auth_req god "$([[ "${1:-on}" == off ]] && echo off || echo on)" ;;
  kill) need_game; auth_req kill "${1#\#}" ;;
  record)
    touch "$STATE/recording"; render on
    trap 'rm -f "$STATE/recording"; [[ "${DUNGEON_PLAY_RENDER:-}" == always ]] || render off' EXIT
    ffmpeg -loglevel error -y -f x11grab -draw_mouse 0 -framerate 30 -video_size "$SIZE" -i "$DISP" \
      -t "$1" -pix_fmt yuv420p "$2" ;;
  sheet)
    ffmpeg -loglevel error -y -i "${1:?video}" -vf "fps=${3:-2},scale=320:-1,tile=4x3" -frames:v 1 "${2:?png}"
    echo "$2" ;;
  mp)
    task="${1:?gradle task}"; host="${2:?host player name, or - to keep the default}"; shift 2
    base="${DISP#:}"
    # Without DUNGEON_PLAY_DISPLAY, take the first run of free displays from :100 on.
    if [[ -z "${DUNGEON_PLAY_DISPLAY:-}" ]]; then
      mkdir -p /tmp/dungeon-play
      run_free() {
        local j own
        for j in $(seq 0 "$#"); do
          own=""; ((j == 0)) && [[ -z "${DUNGEON_PLAY_PORT:-}" ]] && own=$((50000 + base))
          flock -n "/tmp/dungeon-play/$((base + j)).lock" true && [[ -z "$(taken $((base + j)) "$own")" ]] || return 1
        done
      }
      while ! run_free "$@"; do base=$((base + 1)); done
      use_display "$base"
    fi
    # Joiners connect to the host's port; the host start checks its own port.
    on() {
      local d="$1"; shift
      if ((d == base)); then DUNGEON_PLAY_DISPLAY=":$d" "$0" "$@"; else DUNGEON_PLAY_DISPLAY=":$d" DUNGEON_PLAY_PORT="$PORT" "$0" "$@"; fi
    }
    # Walk the menu until the hero is in the game: decline tracking, fill in the name once its field
    # shows (- keeps the default), then confirm the name form and character selection, if any.
    enter() {
      local n=120 filled=""
      while ((n-- > 0)); do
        s="$(on "$1" state)"
        grep -q '^hero ' <<<"$s" && return 0
        if [[ "$decline" != - ]] && grep -qF "Button \"$decline\"" <<<"$s"; then on "$1" press "$decline" >/dev/null
        elif [[ "$2" != - && -z "$filled" ]] && grep -q 'Field "' <<<"$s"; then on "$1" fill '' "$2" >/dev/null; filled=1
        elif grep -qF "Button \"$confirm\"" <<<"$s"; then on "$1" press "$confirm" >/dev/null; fi
        sleep 0.5
      done
      die "player on :$1 did not reach the game within 60 s; last state:"$'\n'"$s"
    }
    # All games start at once; then host, then join one after another.
    pids=(); i=0; failed=0; out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
    joiners=($(seq $((base + 1)) $((base + $#))))
    for d in "$base" "${joiners[@]}"; do on "$d" start "$task" >"$out/$d" 2>&1 & pids+=($!); done
    # A failed start has cleaned up itself (its display may belong to another agent); stop the others.
    started=(); k=0
    for d in "$base" "${joiners[@]}"; do
      if wait "${pids[k]}"; then started+=("$d"); else failed=1; fi; k=$((k + 1))
    done
    if ((failed)); then
      cat "$out"/* >&2
      for d in "${started[@]}"; do on "$d" stop >/dev/null 2>&1 || true; done
      die "start failed"
    fi
    # From here on, stop on the host also stops the joined clients.
    for d in "${joiners[@]}"; do echo ":$d" >>"$STATE/peers"; done
    trap 'rm -rf "$out"; on "$base" stop >/dev/null 2>&1 || true' EXIT
    # The menu's labels in the room's language; rooms may rename Host Game or have no Join Game.
    labels="$(on "$base" menu)"; [[ "$labels" != error* ]] || die "$labels"
    label() { sed -n "s/^$1\t//p" <<<"$labels"; }
    confirm="$(label confirm)"; decline="$(label decline)"
    (($# == 0)) || [[ "$(label join)" != - ]] || die "this room's menu has no Join Game; mp can start the host only"
    on "$base" press "$(label host)" >/dev/null
    enter "$base" "$host"
    # The hosted server's hook answers world and setup commands for every client.
    for _ in $(seq 120); do chan_ok "$STATE/server" && break; sleep 0.25; done
    chan_ok "$STATE/server" || die "hosted server hook did not come up; log: $STATE/game.log"
    for name in "$@"; do
      i=$((i + 1)); d=$((base + i))
      on "$d" press "$(label join)" >/dev/null
      enter "$d" "$name"
    done
    trap 'rm -rf "$out"' EXIT
    echo "host $(on "$base" state | sed -n 's/^hero \([^ ]*\).*/\1/p') on :$base, port $PORT"
    i=0; for name in "$@"; do i=$((i + 1)); echo "$name on :$((base + i))"; done
    echo "use DUNGEON_PLAY_DISPLAY=:<display> per player; stop on :$base stops all" ;;
  stop)
    [[ -d "$STATE" ]] || die "nothing running on $DISP"
    if [[ -f "$STATE/peers" ]]; then
      while read -r peer; do DUNGEON_PLAY_DISPLAY="$peer" "$0" stop || true; done <"$STATE/peers"
    fi
    if [[ -f "$STATE/window" ]]; then xdotool windowclose "$(cat "$STATE/window")" 2>/dev/null || true; fi
    # Wait for the windowed game JVM to finish closing (the level editor saves then); a hosted
    # server it started keeps running on its own and is ended below.
    game_jvm() { local f; for f in "$STATE"/pids/*; do [[ "$(cat "$f" 2>/dev/null)" == game ]] && kill -0 "${f##*/}" 2>/dev/null && return 0; done; return 1; }
    for _ in $(seq 40); do alive game && game_jvm || break; sleep 0.25; done
    # Everything started for this display (gradle client, game JVM, hosted server child) carries
    # DUNGEON_PLAY_STATE in its environment; the shared Gradle daemon is spared.
    owned() {
      grep -lsx "DUNGEON_PLAY_STATE=$STATE" --null-data /proc/[0-9]*/environ | cut -d/ -f3 \
        | while read -r p; do grep -qs GradleDaemon "/proc/$p/cmdline" || echo "$p"; done || true
    }
    for p in $(owned); do kill "$p" 2>/dev/null || true; done
    sleep 1
    for p in $(owned); do kill -9 "$p" 2>/dev/null || true; done
    if alive xvfb && grep -qsa "Xvfb" "/proc/$(cat "$STATE/xvfb.pid")/cmdline"; then kill "$(cat "$STATE/xvfb.pid")"; fi
    rm -rf "$STATE.last"; mv "$STATE" "$STATE.last"
    echo "stopped $DISP; log kept in $STATE.last/game.log" ;;
  *) sed -n "2,/^set -euo/p" "$0" | sed "\$d"; exit 1 ;;
esac
