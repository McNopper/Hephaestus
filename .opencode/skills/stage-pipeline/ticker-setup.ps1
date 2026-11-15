# ticker-setup.ps1 - arm the V-pipeline ticker (U-110 chunk 3, unified per
# owner directive 2026-11-14: ONE schedule walks the whole V step by step).
#
# Creates ONE steward session and ONE experimental schedule; each tick is
# ONE pass over all stages in canonical V order under the global spawn cap.
#
# Requirements:
#   - the running opencode service (url + password from service.json, same
#     pattern the Eclipse host uses), or -BaseUrl/-Password overrides
#   - "experimental": { "schedules": true } in the service config
#   - the stage-pipeline skill (this directory)
#
# Usage: pwsh -NoProfile -File ticker-setup.ps1 [-IntervalMinutes 15] [-Cap 4]
param(
    [int]$IntervalMinutes = 15,
    [int]$Cap = 4,
    [string]$BaseUrl,
    [string]$Token,
    [string]$Directory,
    [string]$ParentSessionId
)

$ErrorActionPreference = "Stop"

if (-not $BaseUrl -or -not $Password) {
    $serviceJson = Join-Path $env:USERPROFILE ".local\state\opencode\service.json"
    if (-not (Test-Path $serviceJson)) {
        throw "service.json not found at $serviceJson - is the opencode service running?"
    }
    $reg = Get-Content $serviceJson -Raw | ConvertFrom-Json
    $BaseUrl = $reg.url
    $Token = $reg.password
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
# steward session + a single experimental schedule; each tick is ONE pass
# over all stages in canonical V order under the global spawn cap.
$session = Invoke-Api -Method "POST" -Path "/api/session$suffix" -Body @{ title = "ticker-v"; parentID = $ParentSessionId }
$sessionId = if ($session.data) { $session.data.id } else { $session.id }
if (-not $sessionId) { throw "session creation returned no id" }
$stages = "requirements, system, architecture, design, implementation, test-implementation, test-design, test-architecture, test-system, test-requirements"
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
    "with stage/epic/basis) plus the paired test ticket; (5) READY tickets: plan them " +
    "into the ticker-v sprint (task_plan_sprint) then claim under the spawn cap of " +
    "$Cap concurrent subagents (check your running subagents first - never exceed " +
    "it) and spawn one background subagent per claimed ticket with the fulfillment " +
    "brief: ticket + basis artifacts + skills, finish this exact ticket, record the " +
    "artifact, set in-review - never done. After the last stage, close the pass " +
    "with a digest. An empty V is a successful pass."
$body = @{
    sessionID = $sessionId
    name = "ticker-v"
    when = @{ type = "every"; every = "$IntervalMinutes minutes" }
    prompt = $prompt
}
$schedule = Invoke-Api -Method "POST" -Path "/api/experimental/schedule" -Body $body
$scheduleId = if ($schedule.data) { $schedule.data.id } else { $schedule.id }
if ($scheduleId) {
    Write-Host "[ticker] ticker-v armed: session $sessionId, schedule $scheduleId, every $IntervalMinutes min, cap $Cap"
} else {
    throw "schedule creation returned no id"
}
