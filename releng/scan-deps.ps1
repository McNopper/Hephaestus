# releng/scan-deps.ps1 - the SCA gate entry point (code-quality-matrix: dep vuln).
# Runs osv-scanner - pinned and checksum-verified - over the reactor and the npm
# lockfile. Used BOTH locally and by .github/workflows/verify.yml (one path,
# never a parallel script).
#
# Policy: any finding fails UNLESS it is listed in scan-deps-baseline.json with a
# written rationale (the recorded-debt exception: unfixed-upstream, dev-only).
#
#   .\releng\scan-deps.ps1              vulnerability scan (the gate)
#   .\releng\scan-deps.ps1 -Licenses    license inventory per dependency (U-089:
#                                        matrix Java/License cell - the cited
#                                        mycila goal is gone from maintained
#                                        versions, osv-scanner covers it free)
#
# Exit 0 = clean; anything else = findings (printed) or a tool failure.
param(
    [switch]$Quiet,
    [switch]$Licenses
)
$ErrorActionPreference = "Stop"

# Pinned release (google/osv-scanner v2.6.0) with the published asset digests.
$Version = "v2.6.0"
$Assets = @{
    "windows" = @{
        Name = "osv-scanner_windows_amd64.exe"
        Sha256 = "e0ed7644118b717b028c249ee9d3515024e55e8510747ca08906eb96765354d6"
    }
    "linux" = @{
        Name = "osv-scanner_linux_amd64"
        Sha256 = "60c5296637e977b28eeda5c7f13573e447659a632922737f94d11fa7e30ad6ca"
    }
}

function Resolve-Scanner {
    $onPath = Get-Command osv-scanner -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $os = if ($env:OS -eq "Windows_NT") { "windows" } else { "linux" }
    $asset = $Assets[$os]
    $dir = Join-Path $env:LOCALAPPDATA "osv-scanner\$Version"
    $exe = Join-Path $dir $asset.Name
    if (Test-Path $exe) { return $exe }

    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $url = "https://github.com/google/osv-scanner/releases/download/$Version/$($asset.Name)"
    $tmp = "$exe.download"
    Write-Host "downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    $actual = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $asset.Sha256) {
        Remove-Item $tmp -Force
        throw "checksum mismatch for $($asset.Name): expected $($asset.Sha256), got $actual"
    }
    Move-Item $tmp $exe -Force
    if ($os -ne "windows") { & chmod +x $exe }
    return $exe
}

function Get-ScanRoot {
    # one recursive scan: every reactor pom.xml plus the chat-web lockfile;
    # osv-scanner respects .gitignore by default (target/ and node_modules/ stay out)
    return (Join-Path (Split-Path -Parent $PSScriptRoot) "eclipse")
}

$scanner = Resolve-Scanner
$root = Get-ScanRoot

if ($Licenses) {
    # U-089 license inventory (matrix Java/License): report the SPDX license of
    # every discovered dependency. Report-only - allowlist enforcement lands once
    # this landscape has been reviewed (QUALITY.md row documents the cell).
    if (-not $Quiet) {
        Write-Host "osv-scanner $Version ($scanner) - license inventory"
        Write-Host "scanning: $root"
    }
    # Native-call hardening: SNAPSHOT-resolution stderr chatter must not kill
    # the script under PS 5.1's Stop policy (same pattern as the vuln scan).
    $ErrorActionPreference = "Continue"
    & $scanner scan source -r $root --licenses
    $licenseExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    # Exit semantics: osv's code 1 here means "known vulnerabilities reported"
    # (the baseline gate lives in the DEFAULT mode of this script) - the license
    # inventory itself succeeded. Codes above 1 are tool failures and pass through.
    if ($licenseExit -le 1) { exit 0 }
    exit $licenseExit
}

$baselinePath = Join-Path $PSScriptRoot "scan-deps-baseline.json"
$baseline = @()
if (Test-Path $baselinePath) {
    # PS 5.1 quirk: ConvertFrom-Json does NOT enumerate a top-level array (it
    # arrives as one Object[]), PS 7 does - normalize for both.
    $parsed = Get-Content $baselinePath -Raw | ConvertFrom-Json
    if ($null -ne $parsed) {
        if ($parsed -is [array]) { $baseline = $parsed } else { $baseline = @($parsed) }
    }
}
$baselineKeys = @{}
foreach ($entry in $baseline) {
    $baselineKeys["$($entry.id)|$($entry.package)"] = $entry
}

if (-not $Quiet) {
    Write-Host "osv-scanner $Version ($scanner)"
    Write-Host "scanning source recursively: $root"
}

$jsonFile = Join-Path ([IO.Path]::GetTempPath()) ("osv-scan-" + [Guid]::NewGuid().ToString("N") + ".json")
# Native-call hardening: stderr chatter from the scanner must never terminate the
# script (PS 5.1 + piped output), so the native call runs with Continue.
$ErrorActionPreference = "Continue"
& $scanner scan source -r $root --format json --output-file $jsonFile
$scanExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"

if (-not (Test-Path $jsonFile)) {
    Write-Error "osv-scanner produced no report (exit $scanExit) - tool failure, not a clean scan"
    if ($scanExit -ne 0) { exit $scanExit }
    exit 1
}
$report = Get-Content $jsonFile -Raw | ConvertFrom-Json
Remove-Item $jsonFile -Force

$findings = @()
if ($report.results) {
    foreach ($res in @($report.results)) {
        foreach ($p in @($res.packages)) {
            foreach ($v in @($p.vulnerabilities)) {
                $findings += [pscustomobject]@{
                    id      = $v.id
                    package = $p.package.name
                    version = $p.package.version
                    source  = $res.source.path
                }
            }
        }
    }
}

$unknown = @()
$suppressed = @()
foreach ($f in $findings) {
    $key = "$($f.id)|$($f.package)"
    if ($baselineKeys.ContainsKey($key)) {
        $suppressed += $f
    } else {
        $unknown += $f
    }
}

if (-not $Quiet) {
    foreach ($f in $suppressed) {
        Write-Host ("baselined (see scan-deps-baseline.json): {0} {1}@{2}" -f $f.id, $f.package, $f.version)
    }
}
if ($unknown.Count -gt 0) {
    Write-Host ""
    Write-Host "VULNERABILITIES FOUND (not baselined):"
    foreach ($f in $unknown) {
        Write-Host ("  {0}  {1}@{2}  ({3})" -f $f.id, $f.package, $f.version, $f.source)
        Write-Host ("      https://osv.dev/vulnerability/{0}" -f $f.id)
    }
    exit 1
}
if ($scanExit -ne 0 -and $findings.Count -eq 0) {
    # no findings but a non-zero exit: tool-level failure (network, args, ...)
    Write-Error "osv-scanner exited $scanExit without reporting findings"
    exit $scanExit
}
if (-not $Quiet) {
    Write-Host ("clean: {0} finding(s), all baselined with rationale" -f $suppressed.Count)
}
exit 0
