# eclipse/releng/coverage-manifest.ps1 - T-008 coverage manifest gate.
#
#   Default (gate mode):  coverage-manifest.ps1 -Since <build-start-ISO>
#   Refresh the baseline: coverage-manifest.ps1 -Update [-Since <iso>]
#
# Collects every test id this build produced (bundles + mojo, both lanes -
# plain maven-surefire and tycho-surefire write the same TEST-<class>.xml into
# target/surefire-reports) plus each test's state and skip reason, normalizes
# it to a sorted, lane-agnostic text manifest, and diffs it against the
# committed baseline releng/coverage-manifest-baseline.txt. Any drift (a test
# id that vanished, appeared, or changed skip reason) fails the build.
#
# -Since marks the build start: ONLY reports written at/after it count, so
# stale leftovers from earlier runs (a test class deleted since the last
# clean) cannot mask a removal - the diff is per-build, not per-tree. This is
# also what makes the -Posgi-parity flip comparable: each lane contributes its
# own fresh reports while the other lane's files age out.
#
# The manifest is deliberately LANE-AGNOSTIC (no plain/OSGi column): the
# parity run's manifest must be byte-identical to a normal run's - that
# equality IS the dual-lane parity proof and the MANIFEST fidelity check
# (the plain lane runs on the manifest-resolved classpath; the OSGi run boots
# against the same manifests).
param(
    # ISO-8601 instant; reports older than this are stale and ignored.
    [string]$Since,
    # Write/overwrite the committed baseline from the collected reports.
    [switch]$Update
)
$ErrorActionPreference = 'Stop'

$releng = $PSScriptRoot
$eclipse = Split-Path -Parent $releng
$baseline = Join-Path $releng 'coverage-manifest-baseline.txt'

$sinceDt = $null
if ($Since) {
    $sinceDt = [datetime]::Parse($Since, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
}

# --- collect -----------------------------------------------------------------------
$lines = New-Object System.Collections.Generic.List[string]
$seen = @{}          # id -> line; duplicate ids (should not happen within one
                     # build) keep the LAST record deterministically by file order
$seenFiles = 0
$staleFiles = 0

$roots = @((Join-Path $eclipse 'bundles'), (Join-Path $eclipse 'mojo'))
foreach ($root in $roots) {
    foreach ($module in (Get-ChildItem -LiteralPath $root -Directory)) {
        $reports = Join-Path $module.FullName 'target\surefire-reports'
        if (-not (Test-Path -LiteralPath $reports)) { continue }
        foreach ($file in (Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' | Sort-Object Name)) {
            if ($sinceDt -and $file.LastWriteTime -lt $sinceDt) { $staleFiles++; continue }
            $seenFiles++
            [xml]$doc = Get-Content -LiteralPath $file.FullName -Raw
            $suite = $doc.testsuite
            if (-not $suite) { continue }
            foreach ($case in @($suite.testcase)) {
                if (-not $case) { continue }
                $cls = if ($case.classname) { [string]$case.classname } else { [string]$suite.name }
                $id = "$($module.Name)|$cls#$($case.name)"
                $state = 'pass'
                $reason = '-'
                if ($case.failure -or $case.error) {
                    $state = 'fail'
                } elseif ($case.skipped) {
                    $state = 'skip'
                    $msg = $case.skipped.GetAttribute('message')
                    if (-not $msg) { $msg = $case.skipped.GetAttribute('type') }
                    $reason = if ($msg) { $msg } else { 'no reason reported' }
                }
                $seen[$id] = "$id|$state|$reason"
            }
        }
    }
}

foreach ($k in $seen.Keys) { $lines.Add($seen[$k]) }
$sorted = $lines.ToArray()
[Array]::Sort($sorted, [StringComparer]::Ordinal)

if ($seenFiles -eq 0) {
    Write-Host "[manifest] ERROR: no test reports newer than the build start were found - the build produced no test output (run without -Since only for local debugging)." -ForegroundColor Red
    exit 1
}

# --- update ------------------------------------------------------------------------
if ($Update) {
    [IO.File]::WriteAllLines($baseline, $sorted, [Text.UTF8Encoding]::new($false))
    Write-Host "[manifest] baseline UPDATED: $($sorted.Count) test ids -> $baseline (stale files ignored: $staleFiles)"
    exit 0
}

# --- diff --------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $baseline)) {
    Write-Host "[manifest] ERROR: baseline missing at $baseline - bootstrap it once from a green run: coverage-manifest.ps1 -Update -Since <build-start>" -ForegroundColor Red
    exit 1
}

$baseLines = @(Get-Content -LiteralPath $baseline)
# line = bundle|class#method|state|reason - the identity of a test id is the
# first TWO fields (bundle + class#method); state/reason are the diffed payload
function Get-TestId([string]$line) { $p = $line -split '\|', 4; "$($p[0])|$($p[1])" }
$baseMap = @{}
foreach ($l in $baseLines) { $baseMap[(Get-TestId $l)] = $l }
$runMap = @{}
foreach ($l in $sorted) { $runMap[(Get-TestId $l)] = $l }

$missing = @($baseMap.Keys | Where-Object { -not $runMap.ContainsKey($_) } | Sort-Object)
$extra = @($runMap.Keys | Where-Object { -not $baseMap.ContainsKey($_) } | Sort-Object)
$changed = @($baseMap.Keys | Where-Object { $runMap.ContainsKey($_) -and $runMap[$_] -ne $baseMap[$_] } | Sort-Object)

if ($missing.Count -eq 0 -and $extra.Count -eq 0 -and $changed.Count -eq 0) {
    Write-Host "[manifest] CLEAN: $($sorted.Count) test ids + skip reasons match the baseline (reports: $seenFiles, stale ignored: $staleFiles)"
    exit 0
}

Write-Host "[manifest] DRIFT against $baseline" -ForegroundColor Red
foreach ($k in $missing) { Write-Host "  MISSING (in baseline, not in this build): $($baseMap[$k])" -ForegroundColor Red }
foreach ($k in $extra) { Write-Host "  EXTRA (in this build, not in baseline): $($runMap[$k])" -ForegroundColor Red }
foreach ($k in $changed) { Write-Host "  CHANGED: $k`n    baseline: $($baseMap[$k])`n    run:      $($runMap[$k])" -ForegroundColor Red }
Write-Host "[manifest] re-baseline intentionally with: eclipse\releng\coverage-manifest.ps1 -Update -Since <build-start>" -ForegroundColor Yellow
exit 1
