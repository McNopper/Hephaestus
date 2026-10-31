# auto-deploy.ps1 - U-034: build main and refresh the dropins automatically
# after a fleet merge-back landed Eclipse-facing changes.
#
# The engine calls this from its post-settle path (the AutoDeploy hook) when
# the merged diff touched eclipse/ bundles. Contract (the engine parses stdout):
#
#   DEPLOYED <iso-instant>   new jars landed - the USER only needs to restart
#                            Eclipse (this is the one and only restart notice)
#   NOTHING <reason>         no deploy needed (nothing built / no jars)
#   (exit 2)                 RED BUILD - nothing is deployed; the engine marks
#                            the ticket for review with the failure and raises
#                            NEEDS-HUMAN per the escalation policy
#
# Every deploy is recorded on the marker line and appended to
# .git/opencode-fleet/last-deploy.log (the audit trail).
#
# B-005 dependency: the stdio tool servers run off staged jar copies
# (tasks-tools.ps1 / fleet-tools.ps1), so a reactor rebuild here can never
# rot a running server's classpath.
#
# Usage:
#   .\auto-deploy.ps1                  # build + deploy with the default install
#   .\auto-deploy.ps1 -EclipseRoot X   # target another install (or ECLIPSE_HOME)
param([string]$EclipseRoot = $(if ($env:ECLIPSE_HOME) { $env:ECLIPSE_HOME } else { "C:\eclipse-cpp" }))
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$stamp = [DateTime]::UtcNow.ToString("o")

# 1) the central reactor build - red builds NEVER deploy
& (Join-Path $here "build.ps1") verify
if ($LASTEXITCODE -ne 0) {
    Write-Output "RED build (exit $LASTEXITCODE) - nothing deployed; needs a human"
    exit 2
}

# 2) refresh the dropins (deploy-dev semantics: wipe + copy + clear the OSGi cache)
& (Join-Path $here "deploy-dev.ps1") -EclipseRoot $EclipseRoot
if ($LASTEXITCODE -ne 0) {
    Write-Output "RED deploy (exit $LASTEXITCODE) - jars built but not deployed; needs a human"
    exit 2
}

# 3) record the deploy (marker + audit log) and announce it exactly once
$repoRoot = Split-Path -Parent $here
$logDir = Join-Path $repoRoot ".git/opencode-fleet"
try {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    Add-Content -LiteralPath (Join-Path $logDir "last-deploy.log") "$stamp deployed to $EclipseRoot"
} catch {
    Write-Warning "could not record the deploy marker: $($_.Exception.Message)"
}
Write-Output "DEPLOYED $stamp"
