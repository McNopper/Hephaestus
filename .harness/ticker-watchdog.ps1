$ErrorActionPreference = 'Continue'
# ticker-watchdog.ps1 - check-once: reconcile the ticker-v schedule with the desired state.
# Designed to run on a Windows scheduled task every ~5 minutes. Exits after one check.
#
# Desired state:  .harness/ticker-state.json  {"enabled": true|false}
# Enable action:  .opencode/skills/stage-pipeline/ticker-setup.ps1 (fresh session + schedule)
# Disable action: delete every ticker-v schedule (per-session queries) - the schedules are
#                 session-bound and the host cycling eats them stochastically (T-052), so the
#                 disable also terminates the ticker sessions (the durable disable).
#
# The watchdog makes ENABLE self-healing against the T-052 cycling: if the cycling eats the
# schedule while enabled, the next check re-arms. Disable stays durable (nothing re-arms while
# the flag is false).

$ErrorActionPreference = 'Continue'
$repo = 'C:\Development\GitHub\Hephaestus'
$stateFile = Join-Path $repo '.harness\ticker-state.json'
$setupScript = Join-Path $repo '.opencode\skills\stage-pipeline\ticker-setup.ps1'
$serviceJson = Join-Path $env:USERPROFILE '.local\state\opencode\service.json'

if (-not (Test-Path $stateFile)) {
    # no state file: the ticker feature was never configured - nothing to do
    exit 0
}
$state = Get-Content $stateFile -Raw | ConvertFrom-Json
$enabled = [bool]$state.enabled

if (-not $enabled) {
    # disabled: durable - nothing re-arms while the flag is false. Nothing to check.
    exit 0
}

# enabled: reconcile - does a ticker-v schedule exist on any known ticker session?
$pw = (Get-Content $serviceJson -Raw | ConvertFrom-Json).password
$token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:$pw"))
$h = @{ Authorization = "Basic $token" }

# enumerate the repo's sessions and query each for a ticker-v schedule (the ticker-v
# sessions are named ticker-v by the setup script; a session-bound schedule dies with
# the session under the T-052 cycling, so check EVERY session, not just the last one)
$dir = [uri]::EscapeDataString('C:\Development\GitHub\Hephaestus')
$sessions = $null
try {
    $s = Invoke-RestMethod -Uri "http://127.0.0.1:28894/api/session?directory=$dir" -Headers $h -TimeoutSec 30
    $sessions = @($s); if ($s.data) { $sessions = @($s.data) }
} catch {
    # the service is unreachable: nothing to reconcile this cycle
    exit 0
}

$scheduleFound = $false
foreach ($ses in $sessions) {
    $sid = $ses.id
    if (-not $sid) { continue }
    try {
        $sch = Invoke-RestMethod -Uri "http://127.0.0.1:28894/api/experimental/schedule?sessionID=$sid" -Headers $h -TimeoutSec 20
        $list = @($sch); if ($sch.data) { $list = @($sch.data) }
        foreach ($x in $list) {
            if ($x.name -eq 'ticker-v' -and $x.status -eq 'active') {
                $scheduleFound = $true
                break
            }
        }
    } catch { continue }
    if ($scheduleFound) { break }
}

if ($scheduleFound) {
    # enabled and armed: nothing to do
    exit 0
}

# enabled but NO active ticker-v schedule anywhere: the cycling ate it - re-arm
$log = Join-Path $repo '.harness\ticker-watchdog.log'
"[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] enabled but no active ticker-v schedule found - re-arming" | Out-File $log -Append -Encoding utf8
try {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
    & $setupScript -IntervalMinutes 15 -Cap 4 2>&1 | Out-File $log -Append -Encoding utf8
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] re-arm done" | Out-File $log -Append -Encoding utf8
} catch {
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] re-arm FAILED: $($_.Exception.Message)" | Out-File $log -Append -Encoding utf8
}
