$ErrorActionPreference = 'Continue'
# tasks-mcp-watchdog.ps1 - check-once: reconnect the `tasks` MCP stdio server for this
# checkout when its connection is dead. Exits after one check (T-203).
#
# Why: the tasks-tools.ps1 stdio child dies periodically and silently (exit 255 / 4 / 1,
# 2026-11-19 and 2026-11-23) and opencode keeps the endpoint RED until someone notices -
# every agent then loses its task_* surface. This watchdog is the auto-recovery half:
# detect dead -> reconnect ONCE -> log the action. The stderr/exit-code capture in
# eclipse/tasks-tools.ps1 is the diagnosis half.
#
# Pattern precedent: .harness/ticker-watchdog.ps1 - a host-independent Windows scheduled
# task, because a session-bound healer would be eaten by the T-052 cycling exactly like
# the thing it repairs. Healthy => silent exit 0; log only when it acts.
#
#   Health check : GET  /api/mcp?location[directory]=<dir>            (status.status per server)
#   Recovery     : POST /api/experimental/mcp/tasks/connect?location[directory]=<dir>
#                  (the same verb that recovered the 2026-11-23 death by hand)
#   Action log   : .harness/tasks-mcp-watchdog.log
#
# REGISTRATION (mirrors \HephaestusTickerWatchdog: interactive-only, every 5 min, stop
# task after 10 min). NOT created by this script - run once by hand:
#   schtasks /create /tn HephaestusTasksMcpWatchdog /sc minute /mo 5 /ru norbe /it /rl LIMITED /f /tr "pwsh.exe -NoProfile -ExecutionPolicy Bypass -File \"C:\Development\GitHub\Hephaestus\.harness\tasks-mcp-watchdog.ps1\""
# Remove: schtasks /delete /tn HephaestusTasksMcpWatchdog /f

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
$dirParam = [uri]::EscapeDataString($repo)
$log = Join-Path $here 'tasks-mcp-watchdog.log'
$serviceJson = Join-Path $env:USERPROFILE '.local\state\opencode\service.json'

function Write-WatchLog([string]$msg) {
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg" | Out-File -FilePath $log -Append -Encoding utf8
}

# service down => nothing to reconcile this cycle (ticker-watchdog precedent: silent)
if (-not (Test-Path $serviceJson)) { exit 0 }
try {
    $pw = (Get-Content $serviceJson -Raw | ConvertFrom-Json).password
} catch { exit 0 }
$token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:$pw"))
$h = @{ Authorization = "Basic $token" }
$base = 'http://127.0.0.1:28894'

function Get-TasksStatus {
    $r = Invoke-RestMethod -Uri "$base/api/mcp?location[directory]=$dirParam" -Headers $h -TimeoutSec 20
    $list = @($r); if ($r.data) { $list = @($r.data) }
    $entry = $list | Where-Object { $_.name -eq 'tasks' } | Select-Object -First 1
    if (-not $entry) { return 'absent' }
    if ($entry.status -is [string]) { return $entry.status }
    if ($entry.status.status) { return [string]$entry.status.status }
    return 'unknown'
}

try {
    $status = Get-TasksStatus
} catch {
    # the service is unreachable or still starting: nothing to do this cycle
    exit 0
}

# connected/connecting are healthy (a mid-reconnect 'connecting' must not be raced);
# everything else (failed/disconnected/error/absent/...) is a dead endpoint.
if ($status -eq 'connected' -or $status -eq 'connecting') { exit 0 }

Write-WatchLog "tasks MCP status=$status for $repo - reconnecting once"
try {
    $null = Invoke-RestMethod -Method Post -Uri "$base/api/experimental/mcp/tasks/connect?location[directory]=$dirParam" -Headers $h -TimeoutSec 60
    $after = Get-TasksStatus
    Write-WatchLog "reconnect issued; status after=$after"
    if ($after -ne 'connected') { Write-WatchLog "RECONNECT DID NOT STICK (status=$after) - needs a human look" }
} catch {
    Write-WatchLog "reconnect FAILED: $($_.Exception.Message)"
}
