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
# S6 (T-223): the endpoint and the scheduled-task name are DERIVED (or overridable),
# so the same script works in any checkout on any machine:
#   -Repo     default: this script's own repo (parent of its .harness/ directory)
#   -BaseUrl  default: the running opencode service's `url` from the registration file
#             service.json (~/.local/state/opencode/service.json, field "url"), falling
#             back to http://127.0.0.1:28894 (this machine's historical port); the
#             password was already read from that same file
#   -TaskName default: "<repo folder>TasksMcpWatchdog" - THIS checkout resolves to
#             HephaestusTasksMcpWatchdog: the name this script WOULD register under
#             (registration is a one-time MACHINE step - see the run-once note below;
#             whether a task of that name exists is machine state, check with
#             `schtasks /query /tn <name>` - never asserted here)
#   -DryRun   print the resolved values + the registration command, exit - no health
#             check, no API call, no reconnect
param(
    [string]$Repo,
    [string]$BaseUrl,
    [string]$TaskName,
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'

# REGISTRATION (mirrors <repo-folder>TickerWatchdog: interactive-only, every 5 min, stop
# task after 10 min). NOT created by this script - it is a one-time MACHINE step run by
# hand, with <repo> = the checkout root and <tn> = <repo folder name>TasksMcpWatchdog
# (derived; -TaskName overrides; -DryRun prints the fully resolved command line):
#   schtasks /create /tn <tn> /sc minute /mo 5 /ru <user> /it /rl LIMITED /f /tr "<pwsh> -NoProfile -ExecutionPolicy Bypass -File \"<repo>\.harness\tasks-mcp-watchdog.ps1\""
#   <pwsh> = the RESOLVED ABSOLUTE pwsh path from (Get-Command pwsh).Source - NEVER bare
#   pwsh.exe: a scheduled task runs with machine PATH only, where the Appx alias does
#   not resolve (the run fails 0x80070002; the machine-level re-registration is tracked
#   as T-228). Simplest: run this script with -DryRun and paste its register= line.
# Remove: schtasks /delete /tn <tn> /f

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Repo) { $Repo = Split-Path -Parent $here }
$dirParam = [uri]::EscapeDataString($Repo)
$log = Join-Path $here 'tasks-mcp-watchdog.log'
$serviceJson = Join-Path $env:USERPROFILE '.local\state\opencode\service.json'

# base URL from the SAME registration file the password comes from (field "url"),
# resolved BEFORE the health check so -DryRun can report it on a machine where the
# service is not even running; http://127.0.0.1:28894 stays as the last-resort default
if (-not $BaseUrl) {
    $regUrl = $null
    if (Test-Path $serviceJson) {
        try { $regUrl = (Get-Content $serviceJson -Raw | ConvertFrom-Json).url } catch { $regUrl = $null }
    }
    $BaseUrl = if ($regUrl) { [string]$regUrl } else { 'http://127.0.0.1:28894' }
}
if (-not $TaskName) {
    $TaskName = (Split-Path -Leaf $Repo).TrimEnd('\') + 'TasksMcpWatchdog'
}

# RESOLVED absolute pwsh path for the printed register= line (T-223 review rework /
# T-228): bare pwsh.exe resolves only through the USER-PATH Appx alias, while a
# scheduled task runs with machine PATH only - there bare pwsh.exe is not found and
# the task run fails 0x80070002. Empty resolution falls back to bare 'pwsh' and SAYS SO.
$pwshExe = $null
try { $pwshExe = (Get-Command pwsh -ErrorAction Stop).Source } catch { $pwshExe = $null }
if ($pwshExe) {
    $pwshExeNote = ''
} else {
    $pwshExe = 'pwsh'
    $pwshExeNote = ' (FALLBACK: (Get-Command pwsh).Source was empty - bare pwsh, PATH resolution at run time)'
}

if ($DryRun) {
    # resolution proof (S6/T-223): what a scheduled run WOULD use - exits before
    # any service.json password read, health check or reconnect
    "repo=$Repo"
    "baseUrl=$BaseUrl"
    "taskName=$TaskName"
    "pwshExe=$pwshExe$pwshExeNote"
    $exeForTask = if ($pwshExe -match '\s') { '"' + $pwshExe + '"' } else { $pwshExe }
    $register = 'schtasks /create /tn {0} /sc minute /mo 5 /it /rl LIMITED /f /tr "{1} -NoProfile -ExecutionPolicy Bypass -File \"{2}\""' -f $TaskName, $exeForTask, "$Repo\.harness\tasks-mcp-watchdog.ps1"
    "register=$register"
    exit 0
}

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
$base = $BaseUrl

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

Write-WatchLog "tasks MCP status=$status for $Repo - reconnecting once"
try {
    $null = Invoke-RestMethod -Method Post -Uri "$base/api/experimental/mcp/tasks/connect?location[directory]=$dirParam" -Headers $h -TimeoutSec 60
    $after = Get-TasksStatus
    Write-WatchLog "reconnect issued; status after=$after"
    if ($after -ne 'connected') { Write-WatchLog "RECONNECT DID NOT STICK (status=$after) - needs a human look" }
} catch {
    Write-WatchLog "reconnect FAILED: $($_.Exception.Message)"
}
