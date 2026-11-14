# ticker-setup.ps1 - arm the per-stage schedule tickers (U-110 chunk 3).
#
# Creates one steward session per stage and one experimental schedule per
# session; the schedule trigger is the stage's sprint kickoff (the tick).
#
# Requirements:
#   - the running opencode service (url + password from service.json, same
#     pattern the Eclipse host uses), or -BaseUrl/-Password overrides
#   - "experimental": { "schedules": true } in the service config
#   - the stage-pipeline skill (this directory)
#
# Usage: pwsh -NoProfile -File ticker-setup.ps1 [-IntervalMinutes 15] [-Cap 4]
#        [-Stages requirements,system,architecture,design,implementation]
param(
    [int]$IntervalMinutes = 15,
    [int]$Cap = 4,
    [string[]]$Stages = @("requirements", "system", "architecture", "design", "implementation"),
    [string]$BaseUrl,
    [string]$Token
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

$armed = 0
foreach ($stage in $Stages) {
    $session = Invoke-Api -Method "POST" -Path "/api/session" -Body @{ title = "ticker-${stage}" }
    $sessionId = $session.id
    if (-not $sessionId) {
        Write-Warning "session creation returned no id for stage ${stage} - skipped"
        continue
    }
    $prompt =
        "You are the ${stage} stage ticker. Load the stage-pipeline skill and run ONE tick " +
        "for the '$stage' stage: (1) readiness scan via task_readiness, take only '$stage' " +
        "tickets; (2) BLOCKED tickets escalate back to their basis (task_set_blocked on the " +
        "basis, NEEDS-HUMAN when stuck); (3) DONE tickets divide: create the linked " +
        "downstream ticket(s) per the division map (task_create with stage/epic/basis) plus " +
        "the paired test ticket; (4) READY tickets: claim under the spawn cap of $Cap " +
        "concurrent subagents (check your running subagents first - never exceed it) and " +
        "spawn one background subagent per ticket with the fulfillment brief: ticket + " +
        "basis artifacts + skills, finish this exact ticket, record the artifact, set " +
        "in-review - never done; (5) close the tick. An empty backlog is a successful tick."
    $schedule = Invoke-Api -Method "POST" -Path "/api/experimental/schedule" -Body @{
        sessionID = $sessionId
        when = "every $IntervalMinutes minutes"
        prompt = $prompt
    }
    if ($schedule.id) {
        Write-Host "[ticker] $stage armed: session $sessionId, schedule $($schedule.id), every $IntervalMinutes min, cap $Cap"
        $armed++
    } else {
        Write-Warning "${stage}: schedule creation returned no id"
    }
}
Write-Host "[ticker] $armed stage ticker(s) armed. Disarm: delete the schedules (experimental.schedule.delete) or toggle the Board switch."
