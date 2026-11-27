# ticker-watchdog.ps1 - check-once: reconcile the ticker-v automation with the desired state.
# Designed to run on a Windows scheduled task every ~5 minutes. Exits after one check.
#
# Desired state:  .harness/ticker-state.json  {"enabled": true|false}
# Enable action:  .opencode/skills/stage-pipeline/ticker-setup.ps1 (fresh session + automation)
# Disable action: delete every ticker-v automation (per-session queries) - the automations are
#                 session-bound and the host cycling eats them stochastically (T-052), so the
#                 disable also terminates the ticker sessions (the durable disable).
#
# The watchdog makes ENABLE self-healing against the T-052 cycling: if the cycling eats the
# automation while enabled, the next check re-arms. Disable stays durable (nothing re-arms while
# the flag is false).
#
# S6 (T-223): nothing machine-specific is pinned anymore - every value is DERIVED (or
# overridable), so the same script works in any checkout on any machine:
#   -Repo     default: this script's git root (the ticker-setup.ps1:70-80 pattern);
#             outside git it falls back to the parent of its own directory (.harness/..)
#   -BaseUrl  default: the running opencode service's `url` from the registration file
#             service.json (~/.local/state/opencode/service.json, field "url"), falling
#             back to http://127.0.0.1:28894 (this machine's historical port)
#   -TaskName default: "<repo folder>TickerWatchdog" - THIS checkout resolves to
#             HephaestusTickerWatchdog: the name this script WOULD register under
#             (registration is a one-time MACHINE step run outside the script - this
#             script only prints the command; whether a task of that name exists is
#             machine state, check with `schtasks /query /tn <name>` - never asserted here)
#   -JavaHome default: this machine's JDK; only exported when the path exists (a new
#             machine keeps whatever JAVA_HOME it already has)
#   -DryRun   print the resolved values + the registration command (with the RESOLVED
#             absolute pwsh path, not bare pwsh.exe), exit - no state read, no API
#             call, no re-arm
param(
    [string]$Repo,
    [string]$BaseUrl,
    [string]$TaskName,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.12.1',
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$serviceJson = Join-Path $env:USERPROFILE '.local\state\opencode\service.json'

if (-not $Repo) {
    # repo root from the script's own location - the ticker-setup.ps1:70-80 pattern
    # (git common-dir -> its parent), wrapped so a git-less machine degrades to the
    # .harness-parent fallback instead of dying (a watchdog must never throw)
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $common = $null
    try {
        $common = git -C $scriptDir rev-parse --path-format=absolute --git-common-dir 2>$null
    } catch { $common = $null }
    if ($common) {
        $Repo = Split-Path -Parent ($common.Trim())
    } else {
        $Repo = Split-Path -Parent $scriptDir
    }
}
$stateFile = Join-Path $Repo '.harness\ticker-state.json'
$setupScript = Join-Path $Repo '.opencode\skills\stage-pipeline\ticker-setup.ps1'

if (-not $BaseUrl) {
    $regUrl = $null
    if (Test-Path $serviceJson) {
        try { $regUrl = (Get-Content $serviceJson -Raw | ConvertFrom-Json).url } catch { $regUrl = $null }
    }
    $BaseUrl = if ($regUrl) { [string]$regUrl } else { 'http://127.0.0.1:28894' }
}
if (-not $TaskName) {
    $TaskName = (Split-Path -Leaf $Repo).TrimEnd('\') + 'TickerWatchdog'
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
    # any state file, API call or re-arm
    "repo=$Repo"
    "baseUrl=$BaseUrl"
    "taskName=$TaskName"
    "pwshExe=$pwshExe$pwshExeNote"
    $exeForTask = if ($pwshExe -match '\s') { '"' + $pwshExe + '"' } else { $pwshExe }
    $register = 'schtasks /create /tn {0} /sc minute /mo 5 /it /rl LIMITED /f /tr "{1} -NoProfile -ExecutionPolicy Bypass -File \"{2}\""' -f $TaskName, $exeForTask, "$Repo\.harness\ticker-watchdog.ps1"
    "register=$register"
    exit 0
}

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

# enabled: reconcile - does a ticker-v automation exist on any known ticker session?
$pw = (Get-Content $serviceJson -Raw | ConvertFrom-Json).password
$token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:$pw"))
$h = @{ Authorization = "Basic $token" }

# enumerate the repo's sessions and query each for a ticker-v automation (the ticker-v
# sessions are named ticker-v by the setup script; a session-bound automation dies with
# the session under the T-052 cycling, so check EVERY session, not just the last one)
$dir = [uri]::EscapeDataString($Repo)
$sessions = $null
try {
    $s = Invoke-RestMethod -Uri "$BaseUrl/api/session?directory=$dir" -Headers $h -TimeoutSec 30
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
        $sch = Invoke-RestMethod -Uri "$BaseUrl/api/experimental/automation?sessionID=$sid" -Headers $h -TimeoutSec 20
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

# enabled but NO active ticker-v automation anywhere: the cycling ate it - re-arm
$log = Join-Path $Repo '.harness\ticker-watchdog.log'
"[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] enabled but no active ticker-v automation found - re-arming" | Out-File $log -Append -Encoding utf8
try {
    if ($JavaHome -and (Test-Path $JavaHome)) {
        # machine-local JDK pin, kept only when it actually exists on THIS machine
        $env:JAVA_HOME = $JavaHome
    }
    & $setupScript -IntervalMinutes 5 -Cap 4 2>&1 | Out-File $log -Append -Encoding utf8
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] re-arm done" | Out-File $log -Append -Encoding utf8
} catch {
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] re-arm FAILED: $($_.Exception.Message)" | Out-File $log -Append -Encoding utf8
}
