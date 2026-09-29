# fleet-stop.ps1 - U-038 user surface: stop the fleet's local processes.
#
# The graceful action is the `fleet_shutdown` tool (chat) or the Board's
# Shutdown button - it PARKS admissions (maintenance gate), checkpoints every
# in-flight worker to its task branch and PAUSES the tickets. This script is
# the companion for the local process layer: after the graceful action (or on
# its own when nothing runs unattended) it stops the processes the fleet owns
# on THIS machine and names every pid it touched:
#
#   1. the stdio tool servers (FleetStdioMain / TasksStdioMain JVMs)
#   2. the fleet's spawned `opencode serve` instances (worktree-pinned)
#
# Engine addressing (U-038 chooser): a stop ALWAYS targets local processes -
# the retired V-006 daemon is gone, so there is no remote engine to no-op
# against; "opencode or Eclipse, everything else is reinventing the wheel"
# means the pump lives in Eclipse and this script stops the TUI-side helpers.
#
# Usage:
#   .\fleet-stop.ps1            # stop and report what was stopped
#   .\fleet-stop.ps1 -WhatIf    # only report what WOULD be stopped
param([switch]$WhatIf)
$ErrorActionPreference = "Stop"

$stopped = @()

function Stop-MatchingProcesses([string[]]$needles, [string]$kind) {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and ($needles | Where-Object { $_ -and $_.Length -gt 0 -and $_.CommandLine }) }
    # explicit per-process matching (the pipeline predicate above cannot see $_ of the outer scope safely)
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $cmd = $_.CommandLine
        if (-not $cmd) { return $false }
        foreach ($needle in $needles) {
            if ($needle -and $cmd.Contains($needle)) { return $true }
        }
        return $false
    }
    foreach ($p in $procs) {
        if ($WhatIf) {
            Write-Host "[fleet-stop] would stop $kind pid $($p.ProcessId)" -ForegroundColor Yellow
            continue
        }
        Write-Host "[fleet-stop] stopping $kind pid $($p.ProcessId)" -ForegroundColor DarkGray
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            $script:stopped += "$kind pid $($p.ProcessId)"
        } catch {
            Write-Warning "could not stop $kind pid $($p.ProcessId): $($_.Exception.Message)"
        }
    }
}

# 1) the stdio tool servers (this repo's launchers name their main classes)
Stop-MatchingProcesses @("com.opencode.ide.fleet.FleetStdioMain", "com.opencode.ide.tasks.TasksStdioMain") "tool server"

# 2) the fleet's spawned opencode serve instances (the engine owns these;
#    killing a leaked one is exactly the B-004 recovery this automates)
Stop-MatchingProcesses @("opencode serve", "opencode-serve") "opencode serve"

if ($WhatIf) {
    Write-Host "[fleet-stop] dry run complete - nothing stopped"
} elseif ($stopped.Count -eq 0) {
    Write-Host "[fleet-stop] nothing to stop - no local fleet processes found"
} else {
    Write-Host "[fleet-stop] stopped: $($stopped -join ', ')"
    Write-Host "[fleet-stop] paused tickets resume with a plain status update (the checkpoints survived)."
}
