# releng/pre-push.ps1 - the pre-push gate: CI must pass locally before a push.
#
# Mirrors the blocking job of .github/workflows/verify.yml step-for-step (the
# same four checks, one path per check - never a parallel script):
#   1. releng/lint.ps1                  (PowerShell + Markdown lint)
#   2. releng/check-no-provider-ids.ps1 (the D-004 gate)
#   3. eclipse\build.ps1 clean verify   (the Maven/Tycho build + tests + the
#                                        in-reactor clean-architecture check;
#                                        build.ps1 self-resolves JAVA_HOME)
#   4. releng/scan-deps.ps1             (the SCA gate)
#
# CI's order is build-first; here the cheap checks run first so a lint break
# fails in seconds instead of after a full build. The check SET is identical.
#
# Fail-fast: the first failing step exits non-zero, which blocks the push
# (wired via .githooks/pre-push + `git config core.hooksPath .githooks`,
# a local setting set by releng/init-local-settings.ps1).

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$script:failed = 0

function Invoke-Step {
    param([string]$Name, [scriptblock]$Body)
    Write-Host "[pre-push] === $Name ===" -ForegroundColor DarkGray
    & $Body
    if ($LASTEXITCODE -ne 0) {
        $script:failed = 1
        Write-Host "[pre-push] BLOCKED: '$Name' failed (exit $LASTEXITCODE) - fix it before pushing." -ForegroundColor Red
        exit 1
    }
}

Invoke-Step 'Lint PowerShell and Markdown' { pwsh -NoProfile -File (Join-Path $PSScriptRoot "lint.ps1") }
Invoke-Step 'No provider/model identifiers in committed content (D-004)' { pwsh -NoProfile -File (Join-Path $PSScriptRoot "check-no-provider-ids.ps1") }
# mvnw reads .mvn/wrapper from the CURRENT directory (CI sets working-directory:
# eclipse) - run the build from the eclipse/ directory, not the repo root.
Invoke-Step 'Build and verify (clean)' {
    Push-Location (Join-Path $PSScriptRoot "..\eclipse")
    try { pwsh -NoProfile -File (Join-Path $PSScriptRoot "..\eclipse\build.ps1") clean verify }
    finally { Pop-Location }
}
Invoke-Step 'SCA - scan dependencies for known vulnerabilities' { pwsh -NoProfile -File (Join-Path $PSScriptRoot "scan-deps.ps1") }

Write-Host "[pre-push] all four CI steps green - push allowed." -ForegroundColor Green
exit 0
