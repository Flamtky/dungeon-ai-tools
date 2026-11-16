# Drive a Dungeon game in a window on this Windows desktop. Installed as `dungeon-play` on PATH.
# One game per instance. `start`, `server` and `mp` take the first free instance from 1 on and print it;
# every later command names it first: dungeon-play <instance> <command>. An instance in front of `start`,
# `server` or `mp` requests that one.
# The game's network port is 42000 + instance unless DUNGEON_PLAY_PORT is set on `start`.
# Run `start` from the checkout or worktree under test. State and logs: %LOCALAPPDATA%\Temp\dungeon-play\<instance>\
# (kept as <instance>.last after stop).
#
# Lifecycle
#   start <gradle-task> [gradle args]   game window; returns once the game renders
#   mp <gradle-task> <host-name|-> <name>...   host on this instance, one joined client per name on the
#                                       next instances; returns once every hero is in the game
#   server <gradle-task> [gradle args]  dedicated server (e.g. run<Room>Server); returns once it runs
#   stop                                close the game and every process it spawned (incl. hosted
#                                       server and the clients `mp` started)
# Look
#   shot <file.png> [--crop x,y,w,h] [--zoom n]   screenshot of the game's own frame + the text state
#   look <dir> <name:x,y,zoom>...       world without HUD from fixed cameras into <dir>\<name>.png: x,y = camera
#                                       centre in tiles, zoom 0.35 close-up, about 1 one area, about 2 overview
#   compare <before> <after> <out>      diff two directories of PNGs: changed pixels, side-by-side and marked images
#   state                               hero, inventory, dialogs (+ their texts, containers), UI texts and
#                                       buttons, players, nearest interactables (+ screen px), new events
#   find <name-part>                    every interactable whose name or held item contains <name-part>, with #id
#   events [n]                          what changed: dialogs, inventories, players, items, hit points, sounds
#   log [lines]                         game log without LWJGL/stack-frame noise
#   wait <regex> [seconds]              block until an event since the last action, or the game log, matches
#                                       (default 30 s)
#   record <seconds> <file.mp4>         video of the next seconds; returns at once, the file appears when done
#   sheet <file.mp4> <file.png> [fps]   contact sheet of a video; waits for a recording still running
# Act (game coordinates are tiles, screen coordinates are window pixels = screenshot pixels)
#   goto <x> <y> | goto <id>            walk there along a path (real input); an id stops in interaction range
#   interact <id> | interact <x> <y>    interact with an entity or point; an out-of-range id is an error
#   skip [regex]                        Escape on the topmost dialog until none takes it (cutscene and
#                                       script pages advance); stops at a choice, input or a page
#                                       matching regex; prints the skipped page texts
#   tp <x> <y> | tp <id>                teleport the hero (server-side debug move)
#   press <text>                        click the visible button/field/text containing <text>
#   key <name> [hold-seconds]           press a key (E, Escape, Enter, Space, Left, F4, L-Shift; default hold 0.08 s)
#   type <text>                         type into the focused field
#   fill <field-text> <new-text>        replace the content of the field showing <field-text>
#   click <x> <y> [button]  mouse <x> <y>  drag <x1> <y1> <x2> <y2>  scroll <up|down> [n]
# Set up (server-side debug changes)
#   give <item> [n]                     add n of the item whose id or name contains <item> to the hero
#   hp <n> [id]                         set hit points of the hero or an entity
#   god [off]                           hero cannot die (hit points stay at least 1)
#   kill <id>                           kill an entity (death handling runs) or remove it
# An id is the number `state` prints after #; write 17 or '#17' (an unquoted # starts a comment).
$ErrorActionPreference = 'Stop'
[Threading.Thread]::CurrentThread.CurrentCulture = [cultureinfo]::InvariantCulture

$Skill = Split-Path -Parent $PSScriptRoot
$Root = Join-Path $env:LOCALAPPDATA 'Temp\dungeon-play'
$Noise = '^\s+at |^\s*\.\.\. [0-9]+ more|LWJGL|Unsafe|sun\.misc|^WARNING: |^Note: |Generated internal assets|NOTE: Picked up JDK_JAVA_OPTIONS|connection preamble|Failed connection handshake|^\[Controllers\]'
$Ctx = $null

# PowerShell turns a,b,c into an array and digits into numbers; the commands take plain strings.
$argv = @($args | ForEach-Object { if ($_ -is [array]) { ($_ | ForEach-Object { "$_" }) -join ',' } else { "$_" } })
$Explicit = $argv.Count -gt 0 -and $argv[0] -match '^\d+$'
if ($Explicit) { $first, $argv = $argv[0], @($argv | Select-Object -Skip 1) }

function Fail([string]$Message) { throw [Exception]::new($Message) }
function NowMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
# The hook splits commands at spaces and reads a=b as an option; | and ? cannot occur in a Windows path.
function Enc([string]$Path) { $Path.Replace(' ', '|').Replace('=', '?') }
function Get-FullPath([string]$Path) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path) }
function Get-Jdk([string]$Tool) {
  if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\$Tool.exe")) { "$env:JAVA_HOME\bin\$Tool.exe" } else { $Tool }
}

# Points every per-instance variable at instance $n.
function Use-Id([int]$n) {
  $script:Id = $n
  $script:State = Join-Path $Root "$n"
  $script:Port = if ($env:DUNGEON_PLAY_PORT) { [int]$env:DUNGEON_PLAY_PORT } else { 42000 + $n }
}

# Whether the process a pid file names still runs. Windows reuses pids: a process younger than the
# file is another one.
function Test-Pid([string]$File, [string]$ProcessId) {
  if (-not (Test-Path -LiteralPath $File)) { return $false }
  if (-not $ProcessId) { $ProcessId = Get-Content -LiteralPath $File -TotalCount 1 }
  $p = Get-Process -Id ([int]$ProcessId) -ErrorAction SilentlyContinue
  if (-not $p) { return $false }
  try { $p.StartTime -le (Get-Item -LiteralPath $File).LastWriteTime } catch { $false }
}
function Test-Alive { Test-Pid "$State\launch.pid" }  # the Gradle run of this instance
function Test-Channel([string]$Dir) { Test-Pid "$Dir\pid" }
# The JVMs the hook registered for this instance: the game and a server it hosts.
function Get-Jvm { @(Get-ChildItem "$State\pids" -File -ErrorAction SilentlyContinue | Where-Object { Test-Pid $_.FullName $_.Name }) }
function Get-CleanLog { if (Test-Path "$State\game.log") { Get-Content "$State\game.log" | Where-Object { $_ -notmatch $Noise } } }

# Fails loudly when the game died, instead of acting on a dead window.
function Assert-Game {
  if (Test-Alive) { return }
  if (-not (Test-Path $State)) { Fail "no game on instance $Id (run: dungeon-play start <task>)" }
  Fail ("game on instance $Id is not running (crashed or closed). Last log lines:`n" + ((Get-CleanLog | Select-Object -Last 15) -join "`n"))
}

# One request to the in-game hook listening in $Dir; returns the answer.
function Req([string]$Dir, [int]$Timeout, [string]$Line) {
  if (-not (Test-Channel $Dir)) { Fail "game hook not available on instance $Id (game not started through dungeon-play?)" }
  $rid = "$PID.$(Get-Random)"
  [IO.File]::WriteAllText("$Dir\new.$rid", "$((NowMs) + $Timeout * 1000) $Line`n")
  [IO.File]::Move("$Dir\new.$rid", "$Dir\req.$rid")
  $end = (Get-Date).AddSeconds($Timeout)
  while ((Get-Date) -lt $end) {
    if (Test-Path "$Dir\res.$rid") {
      $text = [IO.File]::ReadAllText("$Dir\res.$rid")
      Remove-Item "$Dir\res.$rid"
      return $text.TrimEnd("`n")
    }
    Start-Sleep -Milliseconds 30
  }
  Remove-Item "$Dir\req.$rid" -ErrorAction SilentlyContinue
  Fail "no answer from the game within ${Timeout}s (frozen or still loading?); the command was not run"
}

# A request to this instance's game window; an error answer fails the command.
function Game([string]$Line, [int]$Timeout = 10) {
  $r = Req "$State\game" $Timeout $Line
  if ($r -like 'error*') { Fail $r }
  $r
}

# The process with world authority: the game itself (single process) or the hosted/dedicated server.
function Get-ServerChannel {
  if ($env:DUNGEON_PLAY_SERVER) { return "$Root\$($env:DUNGEON_PLAY_SERVER)\server" }
  if (Test-Channel "$State\server") { return "$State\server" }
  # A joined client: the server hook listening on the port this client uses.
  foreach ($d in Get-ChildItem $Root -Directory | Where-Object Name -Match '^\d+$') {
    $s = "$($d.FullName)\server"
    if ((Test-Channel $s) -and (Get-Content "$s\port" -ErrorAction SilentlyContinue) -eq $Ctx.Port) { return $s }
  }
  Fail "no dungeon-play server hook on port $($Ctx.Port); set DUNGEON_PLAY_SERVER=<instance of the host>"
}

# Sets $Ctx (Auth, Player, Port, Cam) from the game process.
function Get-Ctx {
  $c = Req "$State\game" 10 'ctx'
  $pick = { param($re) if ($c -match $re) { $Matches[1] } else { '' } }
  $script:Ctx = @{ Auth = & $pick 'authority=([01])'; Player = & $pick 'player=(\S*)'; Cam = & $pick 'cam=(\S*)'; Port = & $pick 'port=(\d*)' }
}
function Get-AuthChannel { if ($Ctx.Auth -eq '1') { "$State\game" } else { Get-ServerChannel } }

# Asks the authoritative process; for player commands on behalf of this instance's hero.
function Req-Auth([string]$Line) {
  Get-Ctx
  $r = Req (Get-AuthChannel) 10 "$Line player=$($Ctx.Player)"
  if ($r -like 'error*') { Fail $r }
  $r
}

# Event lines of this game and, for a joined client, of its server; sorted by time.
function Get-Event {
  $files = @("$State\game\events")
  if ($Ctx -and $Ctx.Auth -ne '1') { try { $files += "$(Get-ServerChannel)\events" } catch {} }
  $files | Where-Object { Test-Path $_ } | ForEach-Object { Get-Content $_ } | ForEach-Object {
    $cut = $_.IndexOf(' ')
    if ($cut -gt 0) { [pscustomobject]@{ Time = [long]$_.Substring(0, $cut); Text = $_.Substring($cut + 1) } }
  } | Sort-Object Time -Stable
}
function Format-Event { process { 'event {0,5:F1}s ago: {1}' -f (((NowMs) - $_.Time) / 1000), $_.Text } }

# The full state, or only the interactables whose name contains $Match.
function Show-State([string]$Match) {
  Get-Ctx
  if (-not $Match) { Req "$State\game" 10 'view' }
  if ($Ctx.Auth -ne '1' -and $Ctx.Player -eq '-') { return }
  try { $chan = Get-AuthChannel } catch { '(no server hook: players/interactables unknown)'; return }
  $world = Req $chan 10 ("world" + $(if ($Ctx.Cam) { " cam=$($Ctx.Cam)" }) + " player=$($Ctx.Player)" + $(if ($Match) { " match=$Match" }))
  if ($world) { $world }
  if ($Match) { return }
  $seen = if (Test-Path "$State\events.seen") { [long](Get-Content "$State\events.seen") } else { 0 }
  $new = @(Get-Event | Where-Object { $_.Time -gt $seen })
  if (-not $new.Count) { return }
  if ($new.Count -gt 15) { "($($new.Count - 15) earlier events: dungeon-play $Id events)" }
  $new | Select-Object -Last 15 | Format-Event
  Set-Content "$State\events.seen" $new[-1].Time
}

# Clicks the widget showing $Text once it is visible and enabled (menus load asynchronously).
function Invoke-Widget([string]$Text) {
  for ($n = 60; ; $n--) {
    $xy = Req "$State\game" 10 "widget $Text"
    if ($xy -notlike 'error*') { break }
    if ($n -le 0) { Fail "$xy (waited 15 s)" }
    Assert-Game
    Start-Sleep -Milliseconds 250
  }
  Game "click $xy 1" | Out-Null
  $xy
}

# An entity id (17 or #17) or a point (x y) as X, Y, Range and, for an entity, the hero's Dist.
function Resolve-Target([string[]]$a) {
  if ($a.Count -eq 1) {
    $p = -split (Req-Auth "find $($a[0].TrimStart('#'))")
    return @{ X = $p[0]; Y = $p[1]; Range = $p[2]; Dist = $p[3]; Entity = $true }
  }
  if ($a.Count -ne 2) { Fail 'name the target as <id> or <x> <y>' }
  @{ X = $a[0]; Y = $a[1]; Range = '0'; Dist = $null; Entity = $false }
}

function Open-Lock([string]$Path) {
  try { [IO.File]::Open($Path, 'OpenOrCreate', 'ReadWrite', 'None') } catch [IO.IOException] { $null }
}

# Why instance $n cannot take a new game, or nothing when it is free; checks port $OwnPort if given.
function Get-Taken([int]$n, $OwnPort) {
  $dir = "$Root\$n"
  $jvms = @(Get-ChildItem "$dir\pids" -File -ErrorAction SilentlyContinue | Where-Object { Test-Pid $_.FullName $_.Name })
  if ((Test-Pid "$dir\launch.pid") -or $jvms.Count) { return "instance $n is running" }
  if ($OwnPort) {
    $net = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()
    $bound = @($net.GetActiveTcpListeners()) + @($net.GetActiveUdpListeners()) | Where-Object Port -EQ $OwnPort
    if ($bound) { return "port $OwnPort of instance $n is in use" }
  }
}

# Waits up to $Seconds for the lock file; returns its handle, or nothing when it stays taken.
function Wait-Lock([string]$Path, [int]$Seconds) {
  for ($k = 0; $k -le $Seconds * 4; $k++) {
    $lock = Open-Lock $Path
    if ($lock) { return $lock }
    Start-Sleep -Milliseconds 250
  }
}

# The javaagent jar, rebuilt when its source is newer. Every start shares it, so builds take turns.
function Build-Agent {
  $build = "$Skill\hook\build"
  $src = "$Skill\hook\agent\dungeonplay\Premain.java"
  $lock = Wait-Lock "$Root\agent.lock" 60
  if (-not $lock) { Fail 'another dungeon-play start has been building the hook agent for 60 s' }
  try {
    if (-not (Test-Path "$build\agent.jar") -or (Get-Item $src).LastWriteTime -gt (Get-Item "$build\agent.jar").LastWriteTime) {
      Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
      New-Item -ItemType Directory -Force "$build\classes" | Out-Null
      & (Get-Jdk javac) -d "$build\classes" $src
      if ($LASTEXITCODE) { Fail 'javac failed for the hook agent (a running game keeps the old jar open: stop it first)' }
      Set-Content "$build\manifest" 'Premain-Class: dungeonplay.Premain'
      & (Get-Jdk jar) cfm "$build\agent.jar" "$build\manifest" -C "$build\classes" .
      if ($LASTEXITCODE) { Fail 'jar failed for the hook agent' }
    }
  } finally { $lock.Dispose() }
  "$build\agent.jar"
}

# start and server: $Mode is window or server, $a the gradle task and its arguments.
function Start-Game([string]$Mode, [string[]]$a) {
  if (-not $a.Count) { Fail 'name a gradle task, e.g. runBasicStarter' }
  if (-not (Test-Path .\gradlew.bat)) { Fail 'run from the Dungeon checkout or worktree' }
  New-Item -ItemType Directory -Force $Root | Out-Null
  # The lock is held for the whole start. A set DUNGEON_PLAY_PORT is a host's port, so it stays unchecked.
  $n = if ($Explicit) { $Id } else { 1 }
  while ($true) {
    $lock = Open-Lock "$Root\$n.lock"
    $own = if ($env:DUNGEON_PLAY_PORT) { $null } else { 42000 + $n }
    $why = if ($lock) { Get-Taken $n $own } else { "another dungeon-play start is running on instance $n" }
    if (-not $why) { break }
    if ($lock) { $lock.Dispose() }
    if ($Explicit) { Fail "$why; stop it or name another instance" }
    $n++
  }
  Use-Id $n
  $build = $null
  $ok = $false
  try {
    $jar = Build-Agent
    Remove-Item -Recurse -Force $State -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force "$State\pids" | Out-Null
    $checkout = (Get-Location).Path
    # Quoted, each gradle argument stays one argument. cmd runs the launch file, and these characters
    # mean something to it even inside quotes.
    foreach ($x in $a) { if ($x -match '["%&|<>^]') { Fail "gradle argument '$x' holds one of `" % & | < > ^, which the launcher cannot pass on" } }
    $gradle =(@(":game:$($a[0])", '--init-script', "$Skill\hook\init.gradle", '--console=plain', '-q') + @($a | Select-Object -Skip 1) |
      ForEach-Object { '"' + $_ + '"' }) -join ' '
    $size = if ($env:DUNGEON_PLAY_SIZE) { $env:DUNGEON_PLAY_SIZE } else { '1280x750' }
    Set-Content "$State\launch.cmd" @(
      '@echo off'
      "set `"DUNGEON_PLAY_STATE=$State`""
      "set `"DUNGEON_PLAY_AGENT_JAR=$($jar.Replace('\', '/'))`""
      "set `"DUNGEON_PLAY_HOOK_SRC=$Skill\hook\src`""
      "set `"DUNGEON_PLAY_PORT=$Port`""
      "set `"DUNGEON_PLAY_SIZE=$size`""
      "cd /d `"$checkout`""
      "call `"$checkout\gradlew.bat`" $gradle > `"$State\game.log`" 2>&1"
    )
    # Gradle builds in one checkout race on game\build, so they take turns; the lock is released
    # once the game JVM runs, and the games themselves start in parallel.
    $hash = [BitConverter]::ToString([Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes($checkout.ToLowerInvariant()))).Replace('-', '').Substring(0, 16)
    $build = Wait-Lock "$Root\build.$hash.lock" 600
    if (-not $build) { Fail 'another dungeon-play build in this checkout has not finished within 600 s' }
    $run = Start-Process -FilePath $env:ComSpec -ArgumentList "/d /c `"$State\launch.cmd`"" -WindowStyle Hidden -PassThru
    Set-Content "$State\launch.pid" $run.Id
    $chan = if ($Mode -eq 'server') { "$State\server" } else { "$State\game" }
    $up = $false
    for ($k = 0; $k -lt 600 -and -not $up; $k++) {
      if ($build -and (Get-ChildItem "$State\pids" -File)) { $build.Dispose(); $build = $null }
      if (Test-Channel $chan) { try { Req $chan 5 'ping' | Out-Null; $up = $true } catch {} }
      if (-not $up) {
        if (-not (Test-Alive)) { Fail ("game exited during start:`n" + ((Get-CleanLog | Select-Object -Last 20) -join "`n")) }
        Start-Sleep -Milliseconds 500
      }
    }
    if (-not $up) { Fail "game did not come up within 300 s; log: $State.last\game.log" }
    $ok = $true
    "$(if ($Mode -eq 'server') { 'server' } else { 'game' }) $($a[0]) runs as instance $Id, port $Port; log: $State\game.log"
  } finally {
    if ($build) { $build.Dispose() }
    # A failed start cleans up after itself, under the lock it still holds.
    if (-not $ok -and (Test-Path $State)) { try { Stop-Locked | Out-Null } catch {} }
    $lock.Dispose()
  }
}

function Stop-Game {
  if (-not (Test-Path $State)) { Fail "nothing running on instance $Id" }
  # Holding the instance lock keeps another agent's start from taking the instance while it is being
  # cleared. A start holds it too: end that start's command instead, it cleans up after itself.
  $lock = Wait-Lock "$Root\$Id.lock" 10
  if (-not $lock) { Fail "instance $Id is starting or stopping in another command; wait for that command or end it" }
  try { Stop-Locked } finally { $lock.Dispose() }
}

function Stop-Locked {
  # A peer is "<instance> <pid of its Gradle run>": an instance another game took over since is left alone.
  if (Test-Path "$State\peers") {
    foreach ($peer in Get-Content "$State\peers") {
      $n, $run = -split $peer
      if ((Get-Content "$Root\$n\launch.pid" -ErrorAction SilentlyContinue) -eq $run) { try { On $n stop } catch {} }
    }
  }
  # Closing the window lets the game shut down in order (the level editor saves then); a hosted
  # server it started keeps running on its own and is ended below.
  if (Test-Channel "$State\game") { try { Req "$State\game" 5 'quit' | Out-Null } catch {} }
  for ($k = 0; $k -lt 40 -and (Get-Jvm | Where-Object { (Get-Content $_.FullName) -eq 'game' }); $k++) { Start-Sleep -Milliseconds 250 }
  foreach ($jvm in Get-Jvm) { Stop-Process -Id ([int]$jvm.Name) -Force -ErrorAction SilentlyContinue }
  # The Gradle run ends with its game; one that still builds is ended with its process tree. The
  # shared Gradle daemon is spared.
  for ($k = 0; $k -lt 20 -and (Test-Alive); $k++) { Start-Sleep -Milliseconds 250 }
  if (Test-Alive) { & taskkill /T /F /PID (Get-Content "$State\launch.pid") *> $null }
  Remove-Item -Recurse -Force "$State.last" -ErrorAction SilentlyContinue
  for ($k = 0; ; $k++) {
    try { Move-Item $State "$State.last"; break } catch { if ($k -ge 20) { throw }; Start-Sleep -Milliseconds 250 }
  }
  "stopped instance $Id; log kept in $State.last\game.log"
}

# Runs a command for another instance.
function On([int]$n) {
  $back = $Id, $Ctx
  Use-Id $n
  try { Invoke-Play $args[0] @($args | Select-Object -Skip 1) } finally { Use-Id $back[0]; $script:Ctx = $back[1] }
}

# Walks the menu of instance $n until its hero is in the game: decline tracking, fill in the name
# once its field shows (- keeps the default), then confirm the name form and character selection.
function Enter-Game([int]$n, [string]$Name, $Labels) {
  $filled = $false
  $s = ''
  for ($k = 0; $k -lt 120; $k++) {
    $s = (On $n state) -join "`n"
    if ($s -match '(?m)^hero ') { return }
    if ($Labels['decline'] -ne '-' -and $s.Contains("Button `"$($Labels['decline'])`"")) { On $n press $Labels['decline'] | Out-Null }
    elseif ($Name -ne '-' -and -not $filled -and $s.Contains('Field "')) { On $n fill '' $Name | Out-Null; $filled = $true }
    elseif ($s.Contains("Button `"$($Labels['confirm'])`"")) { On $n press $Labels['confirm'] | Out-Null }
    Start-Sleep -Milliseconds 500
  }
  Fail "player on instance $n did not reach the game within 60 s; last state:`n$s"
}

function Start-Multiplayer([string[]]$a) {
  if ($a.Count -lt 2) { Fail 'usage: mp <gradle-task> <host-name|-> [name...]' }
  $task, $hostName, $names = $a[0], $a[1], @($a | Select-Object -Skip 2)
  New-Item -ItemType Directory -Force $Root | Out-Null
  # Without a requested instance, take the first run of free instances from 1 on.
  $base = if ($Explicit) { $Id } else { 1 }
  $free = {
    foreach ($j in 0..$names.Count) {
      $lock = Open-Lock "$Root\$($base + $j).lock"
      if (-not $lock) { return $false }
      $lock.Dispose()
      $own = if ($j -eq 0 -and -not $env:DUNGEON_PLAY_PORT) { 42000 + $base }
      if (Get-Taken ($base + $j) $own) { return $false }
    }
    $true
  }
  while (-not $Explicit -and -not (& $free)) { $base++ }
  Use-Id $base
  $all = @($base) + @(for ($j = 1; $j -le $names.Count; $j++) { $base + $j })
  # All games start at once, each in a process of its own; joiners connect to the host's port.
  $out = "$Root\mp.$PID"
  New-Item -ItemType Directory -Force $out | Out-Null
  $hostPort = $env:DUNGEON_PLAY_PORT
  $runs = @{}
  try {
    foreach ($d in $all) {
      $env:DUNGEON_PLAY_PORT = if ($d -eq $base) { $hostPort } else { "$Port" }
      $run = Start-Process pwsh -ArgumentList "-NoProfile -File `"$PSCommandPath`" $d start $task" -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$out\$d.out" -RedirectStandardError "$out\$d.err"
      $null = $run.Handle  # keeps the exit code readable
      $runs[$d] = $run
    }
  } catch {
    Set-Content "$out\launch.err" "could not start a game process: $($_.Exception.Message)"
  } finally {
    $env:DUNGEON_PLAY_PORT = $hostPort
  }
  # A failed start has cleaned up itself (its instance may belong to another agent); stop the others.
  $started = @()
  foreach ($d in $all) {
    if (-not $runs[$d]) { continue }
    $runs[$d].WaitForExit()
    if ($runs[$d].ExitCode -eq 0) { $started += $d }
  }
  $log = (Get-Content "$out\*") -join "`n"
  Remove-Item -Recurse -Force $out
  if ($started.Count -lt $all.Count) {
    foreach ($d in $started) { try { On $d stop | Out-Null } catch {} }
    Fail "$log`nstart failed"
  }
  # From here on, stop on the host also stops the joined clients.
  if ($names.Count) { Set-Content "$State\peers" ($all | Select-Object -Skip 1 | ForEach-Object { "$_ $(Get-Content "$Root\$_\launch.pid")" }) }
  $ok = $false
  try {
    # The menu's labels in the room's language; rooms may rename Host Game or have no Join Game.
    $labels = @{}
    foreach ($l in (Game 'menu') -split "`n") { $key, $text = $l -split "`t", 2; $labels[$key] = $text }
    if ($names.Count -and $labels['join'] -eq '-') { Fail "this room's menu has no Join Game; mp can start the host only" }
    Invoke-Play press @($labels['host']) | Out-Null
    Enter-Game $base $hostName $labels
    # The hosted server's hook answers world and setup commands for every client.
    for ($k = 0; $k -lt 120 -and -not (Test-Channel "$State\server"); $k++) { Start-Sleep -Milliseconds 250 }
    if (-not (Test-Channel "$State\server")) { Fail "hosted server hook did not come up; log: $State\game.log" }
    for ($j = 0; $j -lt $names.Count; $j++) {
      On $all[$j + 1] press $labels['join'] | Out-Null
      Enter-Game $all[$j + 1] $names[$j] $labels
    }
    $ok = $true
  } finally {
    if (-not $ok) { try { Stop-Game | Out-Null } catch {} }
  }
  $hero = if (((Show-State) -join "`n") -match '(?m)^hero (\S+)') { $Matches[1] }
  "host $hero on instance $base, port $Port"
  for ($j = 0; $j -lt $names.Count; $j++) { "$($names[$j]) on instance $($all[$j + 1])" }
  "name the instance per player: dungeon-play <instance> <command>; stop on $base stops all"
}

function Invoke-Play([string]$Command, [string[]]$a) {
  $a = @($a)
  $arg = { param([int]$k, [string]$what) if ($k -ge $a.Count) { Fail "missing argument: $what" }; $a[$k] }
  switch ($Command) {
    'start' { Start-Game window $a }
    'server' { Start-Game server $a }
    'mp' { Start-Multiplayer $a }
    'stop' { Stop-Game }
    'compare' {
      & (Get-Jdk java) '-Djava.awt.headless=true' "$Skill\scripts\Compare.java" (& $arg 0 'before directory') (& $arg 1 'after directory') (& $arg 2 'output directory')
      if ($LASTEXITCODE) { Fail 'the comparison failed' }
    }
    'key' {
      Assert-Game
      $hold = if ($a.Count -gt 1) { [double]$a[1] } else { 0.08 }
      Game "key $(& $arg 0 'key name') $([int]($hold * 1000))" ([int]$hold + 10) | Out-Null
    }
    'type' { Assert-Game; Game "type $(& $arg 0 'text')" | Out-Null }
    'click' { Assert-Game; Game "click $(& $arg 0 'x') $(& $arg 1 'y') $(if ($a.Count -gt 2) { $a[2] } else { 1 })" | Out-Null }
    'mouse' { Assert-Game; Game "mouse $(& $arg 0 'x') $(& $arg 1 'y')" | Out-Null }
    'drag' { Assert-Game; Game "drag $(& $arg 0 'x1') $(& $arg 1 'y1') $(& $arg 2 'x2') $(& $arg 3 'y2')" | Out-Null }
    'scroll' {
      Assert-Game
      $n = if ($a.Count -gt 1) { [int]$a[1] } else { 1 }
      Game "scroll $(if ((& $arg 0 'up or down') -eq 'up') { $n } else { -$n })" | Out-Null
    }
    'press' { Assert-Game; "clicked $(Invoke-Widget ($a -join ' '))" }
    'fill' {
      Assert-Game
      $xy = Invoke-Widget (& $arg 0 'text of the field (empty: first field)')
      Game "erase $($a[0].Length + 30)" | Out-Null
      Game "type $(& $arg 1 'new text')" | Out-Null
      "filled $xy"
    }
    'shot' {
      Assert-Game
      $out = Get-FullPath (& $arg 0 'file.png')
      $options = ''
      for ($k = 1; $k -lt $a.Count; $k += 2) {
        if ($a[$k] -notin '--crop', '--zoom') { Fail "unknown option $($a[$k])" }
        $options += " $($a[$k].Substring(2))=$(& $arg ($k + 1) "value of $($a[$k])")"
      }
      New-Item -ItemType Directory -Force (Split-Path -Parent $out) | Out-Null
      # Hook round trips = whole frames rendered after the last input (hover, tooltips).
      1..3 | ForEach-Object { Game 'ping' | Out-Null }
      Game "shot $(Enc $out)$options" | Out-Null
      Show-State
    }
    'look' {
      Assert-Game
      $out = Get-FullPath (& $arg 0 'output directory')
      $shots = @($a | Select-Object -Skip 1)
      if (-not $shots.Count) { Fail 'name at least one shot: name:x,y,zoom' }
      foreach ($s in $shots) { if ($s -notmatch '^[A-Za-z0-9_-]+:-?[0-9.]+,-?[0-9.]+,[0-9.]+$') { Fail "shot '$s' is not name:x,y,zoom" } }
      New-Item -ItemType Directory -Force $out | Out-Null
      Game "look $(Enc $out) $($shots -join ' ')" 60
    }
    'state' { Assert-Game; Show-State }
    'find' { Assert-Game; Show-State (& $arg 0 'part of an entity name') }
    'events' { Assert-Game; Get-Ctx; Get-Event | Select-Object -Last $(if ($a.Count) { [int]$a[0] } else { 30 }) | Format-Event }
    'log' { Get-CleanLog | Select-Object -Last $(if ($a.Count) { [int]$a[0] } else { 40 }) }
    'wait' {
      Assert-Game
      Get-Ctx
      $re = & $arg 0 'regex'
      $seconds = if ($a.Count -gt 1) { [int]$a[1] } else { 30 }
      $since = if (Test-Path "$State\acted") { [long](Get-Content "$State\acted") } else { NowMs }
      Remove-Item "$State\acted" -ErrorAction SilentlyContinue
      for ($k = $seconds * 4; $k -gt 0; $k--) {
        $hit = Get-Event | Where-Object { $_.Time -ge $since } | Format-Event | Where-Object { $_ -cmatch $re } | Select-Object -First 1
        if (-not $hit) { $hit = Get-CleanLog | Where-Object { $_ -cmatch $re } | Select-Object -First 1 }
        if ($hit) { return $hit }
        Assert-Game
        Start-Sleep -Milliseconds 250
      }
      Fail "no new event and no log line matched /$re/ within ${seconds}s"
    }
    'goto' {
      Assert-Game
      Req-Auth 'blocked' | Out-Null
      $t = Resolve-Target $a
      Game "goto $($t.X) $($t.Y) $($t.Range)" 100
    }
    'interact' {
      Assert-Game
      Req-Auth 'blocked' | Out-Null
      $t = Resolve-Target $a
      if ($t.Dist -and [double]$t.Dist -gt [double]$t.Range) {
        Fail ('error: {0} is out of range (distance {1:F1}, range {2:F1}); run goto first' -f $a[0], [double]$t.Dist, [double]$t.Range)
      }
      Game "interact $($t.X) $($t.Y)"
    }
    'menu' { Assert-Game; Game 'menu' }
    'skip' { Assert-Game; Game "skip $(if ($a.Count) { $a[0] })" 130 }
    'tp' { Assert-Game; $t = Resolve-Target $a; Req-Auth "tp $($t.X) $($t.Y)" }
    'give' { Assert-Game; Req-Auth "give $(& $arg 0 'item id or name part') $(if ($a.Count -gt 1) { $a[1] } else { 1 })" }
    'hp' { Assert-Game; Req-Auth "hp $(& $arg 0 'hit points')$(if ($a.Count -gt 1) { ' ' + $a[1].TrimStart('#') })" }
    'god' { Assert-Game; Req-Auth "god $(if ($a.Count -and $a[0] -eq 'off') { 'off' } else { 'on' })" }
    'kill' { Assert-Game; Req-Auth "kill $((& $arg 0 'entity id').TrimStart('#'))" }
    'record' {
      Assert-Game
      $out = Get-FullPath (& $arg 1 'file.mp4')
      New-Item -ItemType Directory -Force (Split-Path -Parent $out) | Out-Null
      Game "record $(& $arg 0 'seconds') $(Enc $out)"
    }
    'sheet' {
      $video = Get-FullPath (& $arg 0 'video')
      $png = Get-FullPath (& $arg 1 'png')
      for ($k = 0; $k -lt 480 -and (Test-Path -LiteralPath "$video.part"); $k++) { Start-Sleep -Milliseconds 250 }
      & ffmpeg -loglevel error -y -i $video -vf "fps=$(if ($a.Count -gt 2) { $a[2] } else { 2 }),scale=320:-1,tile=4x3" -frames:v 1 $png
      if ($LASTEXITCODE) { Fail "ffmpeg could not read $video" }
      $png
    }
    default {
      if ($Command) { Fail "unknown command '$Command'; dungeon-play without arguments prints every command" }
      foreach ($l in Get-Content $PSCommandPath) { if ($l -notmatch '^#') { break }; $l -replace '^# ?' }
    }
  }
}

Use-Id $(if ($Explicit) { $first } else { 1 })
$command = if ($argv.Count) { $argv[0] } else { '' }
try {
  if ($command -notin 'start', 'server', 'mp', 'sheet', 'compare', '' -and -not $Explicit) {
    Fail 'name the instance that start or mp printed: dungeon-play <instance> <command>'
  }
  # wait also matches what the latest action caused, even when that action has already returned.
  if ($command -in 'key', 'type', 'click', 'drag', 'scroll', 'press', 'fill', 'goto', 'interact', 'skip', 'tp', 'give', 'hp', 'kill' -and (Test-Path $State)) {
    Set-Content "$State\acted" (NowMs)
  }
  Invoke-Play $command @($argv | Select-Object -Skip 1)
} catch {
  [Console]::Error.WriteLine($_.Exception.Message)
  if ($env:DUNGEON_PLAY_DEBUG) { [Console]::Error.WriteLine($_.ScriptStackTrace) }
  exit 1
}
exit 0
