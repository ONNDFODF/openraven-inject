<#
.SYNOPSIS
    One command for a live test: build, start, inject, watch.

.DESCRIPTION
    The loop this exists to shorten is: start the game, find its pid, inject,
    restart it because the agent changed, find the pid again, read the log,
    notice the jar you injected was not the one you built. Every one of those
    steps has cost a session at least once, and two of them are silent.

    Two things make it shorter than it looks:

      * The agent looks for a sidecar file next to itself -- openraven_native.txt
        -- and if it is there it injects the jar that file names, instead of the
        copy embedded in the DLL. So a client change needs "build.cmd client" and
        nothing else: no relink, no loader rebuild.

      * The naming is read off the game's own command line before anything is
        copied, so the right jar is chosen for the process that is actually
        running rather than for the one that was expected.

.PARAMETER Build
    Rebuild the client jar for the detected naming before injecting. By default
    the jar already in dist is used, which is what you want when nothing changed.

.PARAMETER Launch
    Start Forge 1.8.9 with tools\launch\launch-1.8.9.ps1 and wait for its pid.
    Without this, the game has to be running already -- which is the case for
    Lunar and Badlion, whose launchers are their own.

.PARAMETER Restart
    With -Launch: close a running game first. This is the one to reach for after
    a client change, because classes already loaded are never read again and an
    injection into a stale process looks exactly like a fix that did not work.

.PARAMETER Follow
    Keep printing the log after injecting, until Ctrl+C.

.PARAMETER TargetPid
    Inject into this process instead of searching for one.

.EXAMPLE
    .\run.ps1 -Launch -Restart -Build -Follow
    The full loop: rebuild, restart the game, inject, watch.

.EXAMPLE
    .\run.ps1
    The game is already up and the jar is already built: inject and report.
#>
param(
    [switch]$Build,
    [switch]$Launch,
    [switch]$Restart,
    [switch]$Follow,
    [int]$TargetPid = 0,
    [string]$Version = 'The Cloud Client Experience',
    [string]$JavaHome = 'C:\Program Files\Java\jre1.8.0_151'
)

$ErrorActionPreference = 'Stop'
# This file is <repo>/tools/launch/run.ps1, so the repo is two levels up from
# its directory. Get this wrong by one and every path below is still plausible,
# which is how a run once reported itself as "openraven-inject -- openraven-inject".
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$dist = Join-Path $root 'dist'

function Say($message) { Write-Host $message }

# --------------------------------------------------------------- the game

function Get-GameProcesses {
    # A launcher's own JVM is not the game. Matching on the arguments rather than
    # on the image name is what keeps "javaw.exe" from meaning "the launcher
    # updater", which is a process that is running whenever a launcher is.
    $found = @()
    foreach ($p in @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)) {
        $command = $null
        try {
            $command = (Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction SilentlyContinue).CommandLine
        } catch {
            $command = $null
        }
        $looksLikeGame = $command -and (
            $command -match 'net\.minecraft\.client\.main\.main' -or
            $command -match 'net\.minecraft\.launchwrapper' -or
            $command -match 'lunarclient' -or
            $command -match 'badlion' -or
            $command -match '\-\-assetIndex')
        if ($looksLikeGame) {
            $found += [pscustomobject]@{
                Id      = $p.Id
                Memory  = [math]::Round($p.WorkingSet64 / 1MB)
                Command = $command
                Naming  = Get-Naming $command
            }
        }
    }
    return $found
}

function Get-Naming($command) {
    if ($command -match 'badlion') { return 'notch' }
    if ($command -match 'lunarclient') { return 'mcp' }
    if ($command -match 'launchwrapper|forge') { return 'srg' }
    # Nothing in the command line to go on: the jar the agent picks is decided by
    # probing the JVM anyway, so this is only used to choose what to *build*.
    return 'srg'
}

function Wait-ForGame {
    param([int]$Seconds = 180)
    Say "waiting for the game to come up (up to ${Seconds}s)..."

    # A first look, so "the game is not running" and "this shell cannot see other
    # processes" are told apart before the wait rather than after it. They need
    # different fixes and only one of them is worth waiting three minutes for.
    $anyJava = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
    if ($anyJava.Count -eq 0) {
        Say "  no java process is running at all -- start the game first"
    } else {
        $readable = 0
        foreach ($p in $anyJava) {
            try {
                if ((Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction SilentlyContinue).CommandLine) {
                    $readable++
                }
            } catch { }
        }
        if ($readable -eq 0) {
            Say "  $($anyJava.Count) java process(es) running, but none of their command lines can be read"
            Say "  -- this shell is restricted (an agent sandbox does exactly this) and any"
            Say "     process the launcher started will not be visible from here either."
        } else {
            Say "  $($anyJava.Count) java process(es) running, $readable with a readable command line"
        }
    }

    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $game = @(Get-GameProcesses)
        if ($game.Count -ge 1) { return $game[0] }
    }
    throw "no game process appeared within ${Seconds}s. If the game IS running, this shell cannot see it: run the script from a normal command prompt, or pass -TargetPid <n>."
}

function Stop-Game {
    $game = @(Get-GameProcesses)
    foreach ($g in $game) {
        Say "closing pid $($g.Id) so the new build can load..."
        Stop-Process -Id $g.Id -Force -ErrorAction SilentlyContinue
    }
    if ($game.Count -gt 0) { Start-Sleep -Seconds 5 }
}

# ---------------------------------------------------------------- staging

function Set-Sidecar($clientJar) {
    # What tells the agent which jar to inject instead of its own embedded copy.
    # The path has to be absolute: the agent hands it straight to the filesystem,
    # so a relative one resolves against the *game's* working directory and the
    # file that is plainly there reads as missing.
    $sidecar = Join-Path $dist 'openraven_native.txt'
    if ($clientJar) {
        $absolute = [System.IO.Path]::GetFullPath($clientJar)
        [System.IO.File]::WriteAllText($sidecar, $absolute)
        Say "sidecar: $absolute"
    } elseif (Test-Path $sidecar) {
        Remove-Item $sidecar -Force
    }
}

# ---------------------------------------------------------------- injecting

function Invoke-Inject {
    param([int]$Target)

    $agent = Join-Path $dist 'openraven_agent.dll'
    if (-not (Test-Path $agent)) { throw "no agent at $agent -- run build.cmd native" }

    $staged = Join-Path $dist "openraven_native_$Target.dll"
    if (Test-Path $staged) {
        # A staged DLL from an earlier run can be in the way, and it cannot be
        # overwritten while the game has it mapped. Whether that means "already
        # injected" depends on which is newer: pids are reused, so a staged file
        # older than the process is a leftover from a previous life, and one
        # newer than the process is this process's own injection.
        $stagedAt = (Get-Item $staged).LastWriteTime
        $processAt = (Get-Process -Id $Target -ErrorAction SilentlyContinue).StartTime
        if ($processAt -and $stagedAt -gt $processAt) {
            throw "pid $Target was already injected (dist\openraven_native_$Target.dll is newer than the process)." +
                  "`nRestart the game to load a new build: a JVM never re-reads a class it has already defined."
        }
        Say "removing the staged DLL from a previous run (pid $Target was reused)"
        Remove-Item $staged -Force -ErrorAction SilentlyContinue
        if (Test-Path $staged) {
            throw "cannot remove dist\openraven_native_$Target.dll -- it is still mapped." +
                  "`nThat means this process WAS injected: restart the game."
        }
    }
    Copy-Item $agent $staged -Force

    Say "injecting into pid $Target..."

    # The loader is the supported path and the first thing to try. Two ways it
    # can be the wrong tool, and both are the environment rather than the game:
    #
    #   * a security product refuses OpenProcess from a binary it has never seen
    #     -- "error 5" -- while powershell.exe doing the same five calls is not;
    #   * the loader's own process enumeration comes back empty, which is what a
    #     restricted shell does to a child process. It then reports "none running"
    #     for a game that is plainly up, and since the pid is already in hand
    #     there is nothing to gain from letting it look.
    #
    # Either way the same five calls from PowerShell, with the pid we already
    # have, are what works.
    $loader = Join-Path $dist 'openraven_loader.exe'
    $output = & $loader $Target 2>&1 | Out-String
    $refused = $output -match 'error 5'
    $blind = $output -match 'none running|no java processes|look like Minecraft'
    if ($refused -or $blind) {
        if ($blind -and -not $refused) {
            Say "the loader cannot enumerate processes from here (a restricted shell does this); going straight to the pid"
        } else {
            Say "the loader was refused (error 5); retrying through PowerShell"
        }
        $injector = Join-Path $root 'tools\launch\inject.ps1'
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $injector -TargetPid $Target 2>&1 | Out-String
    }
    $output.Trim() -split "`r?`n" | ForEach-Object { if ($_.Trim()) { Say "  $_" } }

    if ($output -match 'cannot open process|did not load|no agent at') {
        throw "the injection failed -- see the lines above"
    }
}

# ------------------------------------------------------------------- log

function Get-LogPath {
    $staged = Join-Path $dist 'openraven-native.log'
    if (Test-Path $staged) { return $staged }
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) 'openraven-native.log'
    if (Test-Path $temp) { return $temp }
    return $null
}

function Show-Report {
    $log = Get-LogPath
    if (-not $log) { Say "no log yet -- the agent writes it next to itself, or in the game's temp dir"; return }
    Say ""
    Say "=== log: $log ==="
    # The lines that answer "did it work": the hook report, and anything the
    # transform refused. The rest of a healthy log is the class list.
    $interesting = Select-String -Path $log -Pattern `
        'hooks registered|callbacks checked|applied \d|MISSING|REFUSING|no insertion point|unresolved|warm-up|client started|start failed|hook report'
    if ($interesting) {
        $interesting | Select-Object -Last 20 | ForEach-Object { Say $_.Line }
    } else {
        Get-Content $log | Select-Object -Last 15 | ForEach-Object { Say $_ }
    }
}

function Follow-Log {
    $log = Get-LogPath
    if (-not $log) { return }
    Say ""
    Say "following $log  (Ctrl+C to stop)"
    Get-Content $log -Wait -Tail 5
}

# ------------------------------------------------------------------- main

Say "openraven-inject live test  ($root)"

$game = $null
if ($TargetPid -gt 0) {
    $game = [pscustomobject]@{ Id = $TargetPid; Memory = 0; Command = ''; Naming = 'srg' }
    Say "target given: pid $TargetPid"
} else {
    if ($Restart) { Stop-Game }
    if ($Launch) {
        if (@(Get-GameProcesses).Count -gt 0 -and -not $Restart) {
            Say "a game is already running and -Restart was not given; injecting into it"
        } else {
            $launcher = Join-Path $root 'tools\launch\launch-1.8.9.ps1'
            & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher -Version $Version -JavaHome $JavaHome |
                ForEach-Object { Say "  $_" }
        }
    }
    $game = @(Wait-ForGame)
}

if ($game.Count -gt 1) {
    Say "more than one candidate process:"
    $game | ForEach-Object { Say ("  pid {0,-6} {1,6} MB  {2}" -f $_.Id, $_.Memory, (Get-Naming $_.Command)) }
    throw "pass -TargetPid <n> to choose one"
}

$target = $game[0]
Say ("target: pid {0}  {1} MB  naming={2}" -f $target.Id, $target.Memory, $target.Naming)

$clientJar = Join-Path $dist "OpenRaven-Inject-$($target.Naming).jar"
if ($target.Naming -eq 'srg') { $clientJar = Join-Path $dist 'OpenRaven-Inject.jar' }

if ($Build) {
    Say ""
    Say "building the $($target.Naming) client..."
    & cmd /c "`"$(Join-Path $root 'build.cmd')`" client $($target.Naming)" 2>&1 |
        Where-Object { $_ -match 'built|ERROR|error:|required hooks missing|references resolve' } |
        ForEach-Object { Say "  $_" }
    if (-not (Test-Path $clientJar)) { throw "the build did not produce $clientJar" }
}

if (Test-Path $clientJar) {
    Set-Sidecar $clientJar
} else {
    # No jar for this naming in dist: fall back to whatever the agent carries,
    # which is a released build and may not be the one just compiled.
    Say "no $([System.IO.Path]::GetFileName($clientJar)) in dist -- using the jar embedded in the agent"
    Set-Sidecar $null
}

Invoke-Inject -Target $target.Id
Show-Report
if ($Follow) { Follow-Log }

Say ""
Say "done. Restart the game before injecting again: loaded classes are never re-read."
