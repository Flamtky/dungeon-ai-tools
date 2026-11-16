#Requires -Version 7
# Links the Dungeon AI tools into a Dungeon checkout and the agents' skill folders, so that a
# `git pull` in this repository updates them in place. Run it again after new files appear.
#
#   ./install.ps1 -Dungeon D:\Repos\Dungeon
#   ./install.ps1 -Dungeon D:\Repos\Dungeon -Skills dungeon-play -SkillDirs ~\.claude\skills, ~\.agents\skills
#   ./install.ps1 -Dungeon D:\Repos\Dungeon -Overlay D:\Repos\my-dungeon-tools
#
# dungeon/ mirrors the checkout: every file is linked at the same relative path and added to the
# checkout's .git/info/exclude. Every folder in skills/ is linked into each skill folder; a
# *-linux folder is the Linux variant of the skill without the suffix and is skipped here.
# An overlay is a private folder with the same layout. Its dungeon/ files are linked like the
# shared ones, its files under skills/<shared skill>/ are linked into that skill, and its other
# skill folders are linked as skills of their own.
# Whatever already sits where a link belongs moves to .backup/ in this repository.
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Dungeon,
    [string[]] $Skills,
    [string[]] $SkillDirs = @("$HOME\.claude\skills"),
    [string] $Overlay
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = $PSScriptRoot
$skillRoot = Join-Path $repo 'skills'
$backup = Join-Path $repo ".backup\$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$Dungeon = (Resolve-Path -LiteralPath $Dungeon).Path
$SkillDirs = $SkillDirs | ForEach-Object { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($_) }

# Frees $Path: removes a link there, moves anything else to the same path below the backup folder.
function Clear-Path([string] $Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if (-not $item) { return }
    if ($item.LinkType) {
        if ($item.PSIsContainer) { [IO.Directory]::Delete($Path) } else { [IO.File]::Delete($Path) }
    } else {
        $kept = Join-Path $backup ($Path -replace ':', '')
        New-Item -ItemType Directory -Force (Split-Path -Parent $kept) | Out-Null
        Move-Item -LiteralPath $Path -Destination $kept
    }
}

# Makes $Path a link to $Target: a junction for a folder, a symbolic link for a file.
function Set-Link([string] $Path, [string] $Target) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($item -and $item.LinkType -and $item.LinkTarget -eq $Target) { return }
    Clear-Path $Path
    New-Item -ItemType Directory -Force (Split-Path -Parent $Path) | Out-Null
    if (Test-Path -LiteralPath $Target -PathType Container) {
        New-Item -ItemType Junction -Path $Path -Target $Target | Out-Null
    } else {
        try {
            New-Item -ItemType SymbolicLink -Path $Path -Target $Target | Out-Null
        } catch {
            throw "Cannot link $Path. File links need Windows Developer Mode (Settings > System > For developers) or an elevated shell. $_"
        }
    }
    Write-Host "$Path -> $Target"
}

# Links every file below $Source into the Dungeon checkout and keeps it out of git there.
function Add-DungeonFiles([string] $Source) {
    if (-not (Test-Path -LiteralPath $Source)) { return }
    $exclude = [IO.Path]::GetFullPath((git -C $Dungeon rev-parse --git-path info/exclude), $Dungeon)
    foreach ($file in Get-ChildItem -LiteralPath $Source -Recurse -File) {
        $rel = [IO.Path]::GetRelativePath($Source, $file.FullName) -replace '\\', '/'
        Set-Link (Join-Path $Dungeon $rel) $file.FullName
        git -C $Dungeon check-ignore -q -- $rel
        if ($LASTEXITCODE -eq 0) { continue }
        # Glob characters in the name are escaped; a pattern already listed is not added again.
        $pattern = '/' + ($rel -replace '([\[\]*?\\])', '\$1')
        $text = (Test-Path -LiteralPath $exclude) ? [IO.File]::ReadAllText($exclude) : ''
        if (($text -split "`r?`n") -contains $pattern) { continue }
        if ($text -and -not $text.EndsWith("`n")) { $text += "`n" }
        [IO.File]::WriteAllText($exclude, "$text$pattern`n")
    }
}

$shared = @(Get-ChildItem -LiteralPath $skillRoot -Directory | Where-Object Name -NotLike '*-linux')
if ($Skills) {
    $unknown = @($Skills | Where-Object { $_ -notin $shared.Name })
    if ($unknown) { throw "Unknown skill(s): $($unknown -join ', '). Available: $($shared.Name -join ', ')" }
    $shared = @($shared | Where-Object Name -In $Skills)
}

Add-DungeonFiles (Join-Path $repo 'dungeon')
foreach ($skill in $shared) {
    foreach ($dir in $SkillDirs) { Set-Link (Join-Path $dir $skill.Name) $skill.FullName }
}

if ($shared.Name -contains 'dungeon-play') {
    # The skill calls `dungeon-play`; this wrapper puts its driver on PATH.
    $bin = Join-Path $HOME '.local\bin'
    $command = Join-Path $bin 'dungeon-play.ps1'
    $wrapper = @(
        "# Puts the dungeon-play skill's driver on PATH; see the header of the script it calls."
        "& `"$skillRoot\dungeon-play\scripts\play.ps1`" @args"
        'exit $LASTEXITCODE'
    )
    $item = Get-Item -LiteralPath $command -Force -ErrorAction SilentlyContinue
    if (-not $item -or $item.LinkType -or (@(Get-Content -LiteralPath $command) -join "`n") -ne ($wrapper -join "`n")) {
        Clear-Path $command
        New-Item -ItemType Directory -Force $bin | Out-Null
        Set-Content -LiteralPath $command -Value $wrapper
        Write-Host "$command -> $skillRoot\dungeon-play\scripts\play.ps1"
    }
    if (($env:PATH -split ';') -notcontains $bin) { Write-Warning "Add $bin to PATH so that agents can call dungeon-play." }
}

if ($Overlay) {
    $Overlay = (Resolve-Path -LiteralPath $Overlay).Path
    Add-DungeonFiles (Join-Path $Overlay 'dungeon')
    $overlaySkills = Join-Path $Overlay 'skills'
    if (Test-Path -LiteralPath $overlaySkills) {
        foreach ($skill in Get-ChildItem -LiteralPath $overlaySkills -Directory) {
            $sharedSkill = Join-Path $skillRoot $skill.Name
            if (Test-Path -LiteralPath $sharedSkill) {
                foreach ($file in Get-ChildItem -LiteralPath $skill.FullName -Recurse -File) {
                    Set-Link (Join-Path $sharedSkill ([IO.Path]::GetRelativePath($skill.FullName, $file.FullName))) $file.FullName
                }
            } else {
                foreach ($dir in $SkillDirs) { Set-Link (Join-Path $dir $skill.Name) $skill.FullName }
            }
        }
    }
}
