# releng/lint.ps1 - the single local+CI entry for the prose/code lint surfaces
# (U-086, code-quality-matrix rows PowerShell + Markdown). One path, never a
# parallel script: CI calls this file; locally you call the same file.
#
#   .\releng\lint.ps1
#
# Exit 0 = both surfaces clean; anything else = findings (printed) or a tool
# failure.
param()
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    # ---- PowerShell: PSScriptAnalyzer with the repo settings ----
    $settings = Join-Path $repo "PSScriptAnalyzerSettings.psd1"
    if (-not (Get-Module -ListAvailable PSScriptAnalyzer)) {
        # non-interactive install (CI runners ship it; bare machines get it once)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ConfirmPreference = 'None'
        Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Confirm:$false -Scope CurrentUser | Out-Null
        Set-PSRepository -Name PSGallery -InstallationPolicy Trusted
        Install-Module PSScriptAnalyzer -Scope CurrentUser -Force -Confirm:$false -AllowClobber
    }
    $findings = Invoke-ScriptAnalyzer -Path $repo -Recurse -Settings $settings
    if ($findings.Count -gt 0) {
        $findings | Format-Table Severity, RuleName, ScriptName, Line -AutoSize | Out-String | Write-Output
        Write-Error "PSScriptAnalyzer: $($findings.Count) finding(s)"
        exit 1
    }
    Write-Host "powershell: clean"

    # ---- Markdown: markdownlint-cli2 with .markdownlint-cli2.yaml ----
    npx -y markdownlint-cli2
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "markdown: clean"

    exit 0
} finally {
    Pop-Location
}
