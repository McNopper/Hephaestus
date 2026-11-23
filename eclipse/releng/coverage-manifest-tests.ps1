# eclipse/releng/coverage-manifest-tests.ps1 - regression suite for the
# coverage-manifest gate's six-case verdict contract (T-213; divides T-209's
# %TEMP%-only demo driver into the repo so the contract stays re-runnable
# after any future edit of coverage-manifest.ps1).
#
# Run (opt-in, on demand - NOT wired into build.ps1 or CI, T-213 AC3):
#   cd eclipse; .\releng\coverage-manifest-tests.ps1
#   pwsh -NoProfile -File eclipse\releng\coverage-manifest-tests.ps1
#
# PREREQUISITE: surefire reports from a RECENT build must exist
# (eclipse/*/target/surefire-reports/TEST-*.xml) and match the committed
# baseline - run `.\build.ps1 verify` first. With zero reports the suite
# fails fast with a 'run a build first' message instead of silently passing;
# with stale reports that no longer match the baseline, case (d) [pristine ->
# CLEAN] fails honestly.
#
# METHOD (the proven T-208/T-209 temp-driver method): each case runs the gate
# under test in a CHILD pwsh, so the gate's own `exit N` maps exactly to the
# captured exit code; every tampered baseline is a temp copy derived from the
# committed baseline. The gate reads only its own $PSScriptRoot-relative
# baseline path (no -Baseline parameter exists), so that path is swapped only
# inside try/finally and restored byte-identically; the committed baseline's
# SHA256 is printed before and after the run and the suite FAILS if it changed
# (`git status --short eclipse/releng/coverage-manifest-baseline.txt` must be
# empty after a run).
#
# HARD-KILL: a hard kill during case (a)'s swap window can leave the committed baseline pointing at the temp copy - git checkout -- eclipse/releng/coverage-manifest-baseline.txt restores it; normal exceptions/Ctrl+C restore via finally, and the next run's hash guard also catches it.
# NOT DURING A BUILD: the suite swaps the REAL committed baseline path, so do not run it while a build.ps1 gate is running (the concurrent gate could read the missing/fixture baseline) - run it before or after builds, not during.
#
# CASES (the T-209 three-verdict contract - BOTH the exit code and the key
# printed line are asserted per case; the suite exits 0 only when ALL six
# expectations hold AND the baseline hash is unchanged, 1 otherwise):
#   (a) missing baseline                -> exit 1 + ERROR
#   (b) removed baseline id (EXTRA)     -> exit 1 + DRIFT + EXTRA
#   (c) tampered both-skip reason       -> exit 1 + CHANGED
#   (d) pristine baseline               -> exit 0 + CLEAN
#   (e) baseline pass -> run skip flip  -> exit 0 + ENV-DRIFT + TOLERATED
#   (f) baseline skip -> run pass flip  -> exit 0 + ENV-DRIFT + TOLERATED
param(
    # The gate under test. The default is the committed gate; point this at a
    # doctored copy (e.g. in %TEMP%) as the NEGATIVE CONTROL: the suite must
    # then print FAIL rows and exit 1. In that mode the baseline path under
    # test lives beside the doctored copy, so the committed baseline is never
    # even swapped.
    [string]$ManifestScript = (Join-Path $PSScriptRoot 'coverage-manifest.ps1')
)
$ErrorActionPreference = 'Stop'

$releng = $PSScriptRoot
$eclipseRoot = Split-Path -Parent $releng
$committedBaseline = Join-Path $releng 'coverage-manifest-baseline.txt'
$gate = (Resolve-Path -LiteralPath $ManifestScript).Path
$gateDir = Split-Path -Parent $gate
# coverage-manifest.ps1 derives ITS baseline path from ITS $PSScriptRoot -
# mirror that derivation here (equal to the committed path in default mode).
$baselineUnderTest = Join-Path $gateDir 'coverage-manifest-baseline.txt'
$utf8 = [Text.UTF8Encoding]::new($false)
$pwshExe = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $pwshExe)) { $pwshExe = (Get-Command pwsh).Source }
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('opencode\coverage-manifest-tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host '=== coverage-manifest gate regression suite (six cases, T-213) ==='
Write-Host "gate under test : $gate"
Write-Host "baseline path   : $baselineUnderTest"

# --- prerequisite: real report XMLs from a recent build ---------------------------
$reportCount = 0
foreach ($root in @((Join-Path $eclipseRoot 'bundles'), (Join-Path $eclipseRoot 'mojo'))) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    foreach ($module in (Get-ChildItem -LiteralPath $root -Directory)) {
        $reports = Join-Path $module.FullName 'target\surefire-reports'
        if (-not (Test-Path -LiteralPath $reports)) { continue }
        $reportCount += @(Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml').Count
    }
}
if ($reportCount -eq 0) {
    Write-Host "[manifest-tests] ERROR: no surefire TEST-*.xml reports found under $eclipseRoot - run a build first (cd eclipse; .\build.ps1 verify), then re-run this suite." -ForegroundColor Red
    exit 1
}
Write-Host "reports found   : $reportCount TEST-*.xml"

# --- pristine seed + hash-before (the committed baseline stays read-only) ---------
if (-not (Test-Path -LiteralPath $committedBaseline)) {
    Write-Host "[manifest-tests] ERROR: committed baseline missing at $committedBaseline - bootstrap it once from a green run: coverage-manifest.ps1 -Update -Since <build-start>" -ForegroundColor Red
    exit 1
}
$seed = Join-Path $logDir 'baseline-seed.bin'
Copy-Item -LiteralPath $committedBaseline -Destination $seed -Force
$hashBefore = (Get-FileHash -LiteralPath $committedBaseline -Algorithm SHA256).Hash
Write-Host "baseline SHA256 before : $hashBefore"
$seedLines = [IO.File]::ReadAllLines($seed)
if ($seedLines.Count -lt 10) { throw "committed baseline seed implausibly small ($($seedLines.Count) lines) - bootstrap it first" }

# --- case fixtures: derive every tampered baseline variant from the SEED only ----
# Each mode returns the variant lines plus the touched line (evidence for the log).
function Get-SeedVariant {
    param([Parameter(Mandatory)][string]$Mode)
    $out = New-Object System.Collections.Generic.List[string]
    $touched = ''
    switch ($Mode) {
        'RemovedId' {
            # drop exactly ONE baseline id -> the run's matching id becomes EXTRA
            $touched = $seedLines[0]
            for ($i = 1; $i -lt $seedLines.Count; $i++) { $out.Add($seedLines[$i]) }
        }
        'TamperedReason' {
            # both-skip with a DIFFERENT reason string (the T-208 tamper case)
            $done = $false
            foreach ($line in $seedLines) {
                $p = $line -split '\|', 4
                if (-not $done -and $p[2] -eq 'skip' -and $p[3] -and $p[3] -ne '-') {
                    $touched = "$($p[0])|$($p[1])|skip|$($p[3]) T213-TAMPERED"
                    $out.Add($touched)
                    $done = $true
                } else { $out.Add($line) }
            }
            if (-not $done) { throw 'case (c): no both-skip row with a reason in the baseline - cannot build the tamper fixture' }
        }
        'FlipPassSkip' {
            # baseline PASS where the run really skipped -> pass<->skip flip
            $done = $false
            foreach ($line in $seedLines) {
                $p = $line -split '\|', 4
                if (-not $done -and $p[2] -eq 'skip') {
                    $touched = "$($p[0])|$($p[1])|pass|-  (run state stays: skip|$($p[3]))"
                    $out.Add("$($p[0])|$($p[1])|pass|-")
                    $done = $true
                } else { $out.Add($line) }
            }
            if (-not $done) { throw 'case (e): no skip row in the baseline - cannot build the flip fixture' }
        }
        'FlipSkipPass' {
            # baseline SKIP where the run really passed -> pass<->skip flip
            $done = $false
            foreach ($line in $seedLines) {
                $p = $line -split '\|', 4
                if (-not $done -and $p[2] -eq 'pass') {
                    $touched = "$($p[0])|$($p[1])|skip|synthetic T-209 state flip (env-dependent here)  (run state stays: pass|-)"
                    $out.Add("$($p[0])|$($p[1])|skip|synthetic T-209 state flip (env-dependent here)")
                    $done = $true
                } else { $out.Add($line) }
            }
            if (-not $done) { throw 'case (f): no pass row in the baseline - cannot build the flip fixture' }
        }
        default { throw "unknown fixture mode: $Mode" }
    }
    return [pscustomobject]@{ Lines = $out.ToArray(); Touched = $touched }
}

# --- child-pwsh runner: the gate's `exit N` becomes the captured exit code -------
function Invoke-GateCase {
    param([Parameter(Mandatory)][string]$Label)
    $log = Join-Path $logDir "$Label.log"
    & $pwshExe -NoProfile -NonInteractive -File $gate *> $log
    $code = $LASTEXITCODE
    Add-Content -LiteralPath $log -Value "=== EXITCODE=$code ==="
    return [pscustomobject]@{
        ExitCode = $code
        Log      = $log
        Text     = [IO.File]::ReadAllText($log)
    }
}

# --- the six-case contract: expected exit + expected key line(s) ------------------
$caseSpecs = @(
    [pscustomobject]@{ Case = '(a)'; Name = 'missing baseline';               ExpectExit = 1; KeyLines = @('[manifest] ERROR: baseline missing') }
    [pscustomobject]@{ Case = '(b)'; Name = 'removed id';                     ExpectExit = 1; KeyLines = @('[manifest] DRIFT', 'EXTRA (in this build, not in baseline):') }
    [pscustomobject]@{ Case = '(c)'; Name = 'tampered both-skip reason';      ExpectExit = 1; KeyLines = @('CHANGED:') }
    [pscustomobject]@{ Case = '(d)'; Name = 'pristine baseline';              ExpectExit = 0; KeyLines = @('[manifest] CLEAN:') }
    [pscustomobject]@{ Case = '(e)'; Name = 'flip baseline pass -> run skip'; ExpectExit = 0; KeyLines = @('[manifest] ENV-DRIFT (state flip tolerated', '[manifest] TOLERATED:') }
    [pscustomobject]@{ Case = '(f)'; Name = 'flip baseline skip -> run pass'; ExpectExit = 0; KeyLines = @('[manifest] ENV-DRIFT (state flip tolerated', '[manifest] TOLERATED:') }
)
$rows = New-Object System.Collections.Generic.List[object]
$notes = New-Object System.Collections.Generic.List[string]

function Add-CaseRow {
    param(
        [Parameter(Mandatory)]$Spec,
        [Parameter(Mandatory)]$Result
    )
    $missing = @($Spec.KeyLines | Where-Object { -not $Result.Text.Contains($_) })
    $exitOk = ($Result.ExitCode -eq $Spec.ExpectExit)
    $keyOk = ($missing.Count -eq 0)
    $rows.Add([pscustomobject]@{
        Case        = $Spec.Case
        Name        = $Spec.Name
        ExpectExit  = $Spec.ExpectExit
        ActualExit  = $Result.ExitCode
        ExitOk      = $exitOk
        KeyOk       = $keyOk
        MissingKeys = $missing
        Log         = $Result.Log
        Pass        = ($exitOk -and $keyOk)
    })
}

# --- run the six cases ------------------------------------------------------------
$fatal = $null
$baselineExisted = Test-Path -LiteralPath $baselineUnderTest
$baselineOriginal = Join-Path $logDir 'baseline-under-test.original'
if ($baselineExisted) { Copy-Item -LiteralPath $baselineUnderTest -Destination $baselineOriginal -Force }

try {
    # (a) missing baseline - the path must be ABSENT. Moved aside here (the only
    # in-repo touch in default mode) and restored byte-identically in finally.
    if (Test-Path -LiteralPath $baselineUnderTest) {
        Move-Item -LiteralPath $baselineUnderTest -Destination (Join-Path $logDir 'baseline-moved-aside.txt') -Force
    }
    Add-CaseRow -Spec $caseSpecs[0] -Result (Invoke-GateCase -Label 'a-missing-baseline')

    # (b) one removed baseline id -> the run's matching id becomes EXTRA (hard)
    $v = Get-SeedVariant -Mode 'RemovedId'
    [IO.File]::WriteAllLines($baselineUnderTest, $v.Lines, $utf8)
    $notes.Add("(b) removed baseline line : $($v.Touched)")
    Add-CaseRow -Spec $caseSpecs[1] -Result (Invoke-GateCase -Label 'b-removed-id')

    # (c) both-skip with a DIFFERENT reason string -> CHANGED stays hard (exit 1)
    $v = Get-SeedVariant -Mode 'TamperedReason'
    [IO.File]::WriteAllLines($baselineUnderTest, $v.Lines, $utf8)
    $notes.Add("(c) tampered baseline line: $($v.Touched)")
    Add-CaseRow -Spec $caseSpecs[2] -Result (Invoke-GateCase -Label 'c-tampered-skip-reason')

    # (d) pristine baseline byte-for-byte -> CLEAN (needs reports that match it)
    Copy-Item -LiteralPath $seed -Destination $baselineUnderTest -Force
    Add-CaseRow -Spec $caseSpecs[3] -Result (Invoke-GateCase -Label 'd-pristine-clean')

    # (e) baseline pass -> run skip: rewrite a skipped id as PASS in the baseline
    $v = Get-SeedVariant -Mode 'FlipPassSkip'
    [IO.File]::WriteAllLines($baselineUnderTest, $v.Lines, $utf8)
    $notes.Add("(e) flip fixture          : baseline $($v.Touched)")
    Add-CaseRow -Spec $caseSpecs[4] -Result (Invoke-GateCase -Label 'e-flip-baseline-pass-run-skip')

    # (f) baseline skip -> run pass: rewrite a passing id as SKIP in the baseline
    $v = Get-SeedVariant -Mode 'FlipSkipPass'
    [IO.File]::WriteAllLines($baselineUnderTest, $v.Lines, $utf8)
    $notes.Add("(f) flip fixture          : baseline $($v.Touched)")
    Add-CaseRow -Spec $caseSpecs[5] -Result (Invoke-GateCase -Label 'f-flip-baseline-skip-run-pass')
}
catch {
    $fatal = $_.Exception.Message
    Write-Host "[manifest-tests] ERROR: suite aborted while running a case: $fatal" -ForegroundColor Red
}
finally {
    # guaranteed byte-identical restore of whatever was at the path originally,
    # even if a case above threw
    if ($baselineExisted) {
        Copy-Item -LiteralPath $baselineOriginal -Destination $baselineUnderTest -Force
    } elseif (Test-Path -LiteralPath $baselineUnderTest) {
        Remove-Item -LiteralPath $baselineUnderTest -Force
    }
}

# --- hash-after + verdict -----------------------------------------------------------
$hashAfter = (Get-FileHash -LiteralPath $committedBaseline -Algorithm SHA256).Hash
Write-Host "baseline SHA256 after  : $hashAfter"
$hashOk = ($hashBefore -eq $hashAfter)
Write-Host "baseline unchanged     : $hashOk"
if (-not $hashOk) {
    Write-Host '[manifest-tests] FAIL: the committed baseline changed during the run - the suite must never modify it (git status must show it clean).' -ForegroundColor Red
}

Write-Host ''
Write-Host ('{0,-5} {1,-30} {2,-9} {3,-7} {4,-7} {5}' -f 'case', 'expectation', 'expected', 'actual', 'keys', 'result')
foreach ($row in $rows) {
    $keysText = if ($row.KeyOk) { 'ok' } else { 'MISSING' }
    $resultText = if ($row.Pass) { 'PASS' } else { 'FAIL' }
    $color = if ($row.Pass) { 'Green' } else { 'Red' }
    Write-Host ('{0,-5} {1,-30} {2,-9} {3,-7} {4,-7} {5}' -f $row.Case, $row.Name, $row.ExpectExit, $row.ActualExit, $keysText, $resultText) -ForegroundColor $color
}
$failed = @($rows | Where-Object { -not $_.Pass })
foreach ($row in $failed) {
    $why = @()
    if (-not $row.ExitOk) { $why += "exit expected $($row.ExpectExit), actual $($row.ActualExit)" }
    if (-not $row.KeyOk) { $why += "missing key line(s): $($row.MissingKeys -join ' | ')" }
    Write-Host ('  {0} {1}: {2}  (log: {3})' -f $row.Case, $row.Name, ($why -join '; '), $row.Log) -ForegroundColor Red
}
foreach ($note in $notes) { Write-Host "  note $note" }

$passedCount = @($rows | Where-Object { $_.Pass }).Count
$allCasesOk = ($rows.Count -eq 6 -and $failed.Count -eq 0 -and $null -eq $fatal)
$suiteOk = ($allCasesOk -and $hashOk)
Write-Host ''
Write-Host "logs: $logDir"
if ($suiteOk) {
    Write-Host "OVERALL: PASS (6/6 cases; baseline SHA256 unchanged: $hashOk)" -ForegroundColor Green
    exit 0
}
$fatalNote = ''
if ($fatal) { $fatalNote = "; fatal: $fatal" }
Write-Host "OVERALL: FAIL ($passedCount/6 cases passed; baseline unchanged: $hashOk$fatalNote)" -ForegroundColor Red
exit 1
