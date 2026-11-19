# ticker-setup.ps1 - arm the V-pipeline ticker (U-110 chunk 3, unified per
# owner directive 2026-11-14: ONE schedule walks the whole V step by step).
#
# Creates ONE steward session and ONE experimental automation; each tick is
# ONE pass over all stages in canonical V order under the global spawn cap.
# Re-running re-arms: the steward binding is reused (refreshed interval +
# prompt, DS-006 supersession clause), never duplicated - the name-lookup
# guard per docs/unified-ticker-design.md §2 C8.
#
# Autonomy-spectrum schedule lane (E6; docs/autonomy-spectrum-design.md
# C-C3-3): this script is the ARM touchpoint (arm at kickoff, W-2 step 1).
# The DISARM touchpoint - a time-boxed horizon end - is the steward's
# pass-final act, contract-landed in the stage-pipeline skill (kickoff
# horizon read + horizon enforcement) and executed via the durable disable
# (.harness/ticker-state.json enabled:false + automation delete) so the
# watchdog cannot re-arm a horizon-ended run. This script stays arm-only;
# no dial value is read or stored here (pump invariance, QA-3).
#
# Requirements:
#   - the running opencode service (url + password from service.json, same
#     pattern the Eclipse host uses), or -BaseUrl/-Token overrides
#   - "experimental": { "automations": true } in the service config
#     (2.3.1 renamed schedules -> automations, #64160; the old "schedules"
#     flag still works - either grants the surface)
#   - the stage-pipeline skill (this directory)
#
# Usage: pwsh -NoProfile -File ticker-setup.ps1 [-IntervalMinutes 5] [-Cap 4]
param(
    [int]$IntervalMinutes = 5,
    [int]$Cap = 4,
    [string]$BaseUrl,
    [string]$Token,
    [string]$Directory,
    [string]$ParentSessionId
)

$ErrorActionPreference = "Stop"

# C8 step 1 precedence (delta #3 fix, T-104): explicit -BaseUrl/-Token win
# over service.json; each missing value falls back individually. The old
# code tested an undefined $Password (the param is $Token) and clobbered
# explicit overrides wholesale.
if (-not $BaseUrl -or -not $Token) {
    $serviceJson = Join-Path $env:USERPROFILE ".local\state\opencode\service.json"
    if (-not (Test-Path $serviceJson)) {
        throw "service.json not found at $serviceJson - is the opencode service running? (or pass -BaseUrl/-Token overrides)"
    }
    $reg = Get-Content $serviceJson -Raw | ConvertFrom-Json
    if (-not $BaseUrl) { $BaseUrl = $reg.url }
    if (-not $Token) { $Token = $reg.password }
}
$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$Token"))

# U-110: ticker sessions must live IN THE REPO and INHERIT the harness config (the
# tasks MCP server + the stage-pipeline skill resolve from the session's project).
# The shared service starts in the home directory, so plain sessions get the
# user-global config only - pixellab, no task tools (observed live 2026-11-14).
# Fix: create the tickers as CHILD sessions of a repo-rooted session (children
# inherit the parent's tools/skills/config context).
if (-not $ParentSessionId) {
    $dir0 = if ($Directory) { $Directory } else { (Get-Location).Path }
    $enc0 = [Uri]::EscapeDataString($dir0)
    $s = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/session?directory=$enc0" -Headers @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$Token")) } -TimeoutSec 30
    $rootSession = $s.data | Where-Object { -not $_.parentID } | Sort-Object { $_.time.updated } -Descending | Select-Object -First 1
    if (-not $rootSession) { throw "no repo-rooted parent session found - open the harness chat first or pass -ParentSessionId" }
    $ParentSessionId = $rootSession.id
}
if (-not $Directory) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $common = git -C $scriptDir rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($common) {
        $repoRoot = Split-Path -Parent ($common.Trim())
        $Directory = Join-Path $repoRoot ""
        if (-not $Directory) { throw "could not resolve the repository root - pass -Directory" }
    } else {
        throw "could not resolve the repository root (no git) - pass -Directory"
    }
}
$enc = [Uri]::EscapeDataString($Directory)
$suffix = "?directory=$enc"

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body)
    $headers = @{ Authorization = $auth }
    $json = if ($Body) { $Body | ConvertTo-Json -Depth 8 -Compress } else { $null }
    if ($json) {
        Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -Headers $headers `
            -ContentType "application/json" -Body $json -TimeoutSec 30
    } else {
        Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -Headers $headers -TimeoutSec 30
    }
}

# ONE schedule walks the whole V (owner directive 2026-11-14): a single
# steward session + a single experimental automation; each tick is ONE pass
# over all stages in canonical V order under the global spawn cap.
function Get-TickerSchedule {
    # ticker-v automations are session-bound (T-052): enumerate the repo's
    # sessions and query each (GET /api/experimental/automation?sessionID=...);
    # a response is either a bare array or the v2 {data:[...]} envelope.
    $sessions = Invoke-Api -Method "GET" -Path "/api/session$suffix"
    $list = @($sessions); if ($sessions.data) { $list = @($sessions.data) }
    $found = @()
    foreach ($ses in $list) {
        if (-not $ses.id) { continue }
        $sch = Invoke-Api -Method "GET" -Path "/api/experimental/automation?sessionID=$($ses.id)"
        $perSession = @($sch); if ($sch.data) { $perSession = @($sch.data) }
        foreach ($x in $perSession) {
            if ($x.name -eq "ticker-v") { $found += $x }
        }
    }
    return $found
}

# Re-arm guard (C8 step 2, delta #1 fix): look up BEFORE creating. Found +
# live steward binding -> reuse it (S-3, DS-002); binding dead (the host
# cycling eats sessions, T-052) or not found -> fresh steward (S-4). The
# automation RECORD is retired and re-created either way, bound to the chosen
# session: the automation API has create/list/get/pause/resume/delete but no
# update, so Q-001 resolves to retire-and-recreate - a re-arm always
# refreshes interval + prompt (DS-006) and never duplicates (INV-1/INV-2).
$tickers = @(Get-TickerSchedule)
# The enumeration is eventually-consistent on the live service: a transient EMPTY read on a
# repo that has armed before made this script conclude "first arm" - it created a SECOND
# steward and deleted nothing (observed 2026-11-17: two ticker-v automations, INV-1 violated).
# Retry the lookup before concluding "no automations yet"; a genuine first arm just pays this
# bounded delay. The post-create INV-1 check below (retried until exactly one) is the backstop
# if this read stays empty while an automation actually exists.
for ($i = 0; $i -lt 10 -and $tickers.Count -eq 0; $i++) {
    Start-Sleep -Milliseconds 300
    $tickers = @(Get-TickerSchedule)
}
$repoSessions = Invoke-Api -Method "GET" -Path "/api/session$suffix"
$sessionList = @($repoSessions); if ($repoSessions.data) { $sessionList = @($repoSessions.data) }
$newest = $tickers | Sort-Object { $_.time.updated } -Descending | Select-Object -First 1
$binding = $null
if ($newest -and $newest.sessionID) {
    $binding = $sessionList | Where-Object { $_.id -eq $newest.sessionID } | Select-Object -First 1
}
if ($binding) {
    $sessionId = $binding.id
} else {
    if ($newest) {
        Write-Host "[ticker] stale ticker-v binding $($newest.sessionID) (session gone) - creating a fresh steward (S-4)"
    }
    $session = Invoke-Api -Method "POST" -Path "/api/session$suffix" -Body @{ title = "ticker-v"; parentID = $ParentSessionId }
    $sessionId = if ($session.data) { $session.data.id } else { $session.id }
    if (-not $sessionId) { throw "session creation returned no id" }
}
foreach ($t in $tickers) {
    Invoke-Api -Method "DELETE" -Path "/api/experimental/automation/$($t.id)" | Out-Null
}
$stages = "requirements, system, architecture, design, implementation, test-implementation, test-design, test-architecture, test-system, test-requirements"
# DS-006 supersession clause (design §2 C3, verbatim; §2 C7 lands it in this
# script's tick-prompt template): static arm-time prompt text, never carried
# state - each firing is a complete, independent pass (E2). It exists because
# DS-002 reuses the steward session on a live re-arm: a refreshed prompt must
# not be second-guessed by an older prompt's parameters in the session history.
$prompt =
    "You are the V-pipeline ticker. Load the stage-pipeline skill and run ONE pass " +
    "through the whole V, stage by stage in this order: $stages. For EACH stage: " +
    "(1) readiness scan via task_readiness, take only this stage's tickets; " +
    "(2) BLOCKED tickets escalate to their basis (task_set_blocked on the basis, " +
    "NEEDS-HUMAN when stuck); (3) IN-REVIEW tickets of this stage get their engine " +
    "verdict: spawn ONE reviewer subagent per ticket (the reviewer agent, read-only) - " +
    "PASS: task_update done; FAIL: task_set_blocked with the reasons; UNCLEAR: one " +
    "retry round-trip to the fulfiller; (4) DONE tickets without downstream children " +
    "divide: create the linked downstream ticket(s) per the division map (task_create " +
    "with stage/basis - the basis chain to the requirement is the scope; " +
    "task_doctor reports missing divisions and missing right-leg joins) plus the " +
    "paired test ticket; (5) READY tickets: plan them " +
    "into the ticker-v sprint (task_plan_sprint) then claim under the spawn cap of " +
    "$Cap concurrent subagents (check your running subagents first - never exceed " +
    "it) and spawn one background subagent per claimed ticket with the fulfillment " +
    "brief: ticket + basis artifacts + skills, finish this exact ticket, record the " +
    "artifact, set in-review - never done; a landed markdown doc must be lint-clean " +
    "(releng/lint.ps1 over the touched files). Once for the whole pass BEFORE the close: " +
    "STAGELESS in-review tickets (no stage - the per-stage scan reports them " +
    "NOT_APPLICABLE and never sees them) get the same engine verdict step, skipping " +
    "tickets owned by your running subagents and tickets with review:human (the owner's " +
    "verdict): one read-only reviewer each under the cap - PASS: task_update done; " +
    "FAIL: task_set_blocked with the reasons (no basis edge: it surfaces to the PO); " +
    "UNCLEAR: the doubt stays as a comment on the in-review ticket. Verdicts only - " +
    "stageless tickets are never divided, claimed or kicked off. After the last stage, close the pass " +
    "with a digest. An empty V is a successful pass. " +
    "SUPERSESSION: this prompt is the current parameterization; it supersedes any earlier tick prompt still visible in this session's history."
# 2.3.1 rename (#64160): CreatePayload is now {sessionID, name, trigger,
# action:{type:"prompt", prompt}} - the old {when, prompt} shape is gone.
$body = @{
    sessionID = $sessionId
    name = "ticker-v"
    trigger = @{ type = "every"; every = "$IntervalMinutes minutes" }
    action = @{ type = "prompt"; prompt = $prompt }
}
try {
    $schedule = Invoke-Api -Method "POST" -Path "/api/experimental/automation" -Body $body
} catch {
    # duplicate-name race on create (C8 error contract): re-lookup and reuse;
    # fail only if the second lookup is also inconsistent
    $raced = Get-TickerSchedule | Select-Object -First 1
    if (-not $raced) { throw }
    $schedule = $raced
    Write-Host "[ticker] create raced an existing ticker-v automation - reused $($schedule.id)"
}
$scheduleId = if ($schedule.data) { $schedule.data.id } else { $schedule.id }
if (-not $scheduleId) { throw "automation creation returned no id" }
# assert exactly one automation and one steward afterwards (C8 step 3, INV-1/INV-2);
# the enumeration lags the POST by a moment on the live service, so re-read until it
# settles on EXACTLY ONE (retrying past 0 alone was not enough: a stale duplicate or a
# lagging delete also transiently misreports - false negatives here made a successful arm
# look like a failure AND let a real duplicate pass, U-075/T-174-era fixes; same semantics:
# the end state MUST be exactly one automation bound to this steward)
$final = @()
for ($i = 0; $i -lt 20 -and $final.Count -ne 1; $i++) {
    $final = @(Get-TickerSchedule)
    if ($final.Count -ne 1) { Start-Sleep -Milliseconds 500 }
}
if ($final.Count -ne 1) { throw "arming invariant violated: $($final.Count) ticker-v automations exist after arming (INV-1: exactly one)" }
if ($final[0].sessionID -ne $sessionId) { throw "arming invariant violated: ticker-v automation bound to $($final[0].sessionID), not the steward $sessionId (INV-2)" }
$sessionsAfter = Invoke-Api -Method "GET" -Path "/api/session$suffix"
$sessionAfter = @($sessionsAfter); if ($sessionsAfter.data) { $sessionAfter = @($sessionsAfter.data) }
if (-not ($sessionAfter | Where-Object { $_.id -eq $sessionId })) { throw "arming invariant violated: steward session $sessionId not found on the service (INV-2)" }
Write-Host "[ticker] ticker-v armed: session $sessionId, automation $scheduleId, every $IntervalMinutes min, cap $Cap"
