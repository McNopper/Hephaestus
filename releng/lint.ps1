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
        # non-interactive install (CI runners ship it; bare machines get it once).
        # pwsh 7.5+ ships PSResourceGet - the supported path there (the legacy
        # Install-PackageProvider NuGet feed is retired and fails on it). Older
        # stacks keep the classic PowerShellGet bootstrap.
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ConfirmPreference = 'None'
        if (Get-Module -ListAvailable Microsoft.PowerShell.PSResourceGet) {
            # PSResourceGet does not create its repository store dir on first use;
            # Install-PSResource -Trust skips the untrusted-repo prompt (no Set-PSResourceRepository
            # needed - it fails on a virgin store where PSResourceRepository.xml does not exist yet)
            $storeDir = Join-Path $env:LOCALAPPDATA "PSResourceGet"
            if (-not (Test-Path $storeDir)) { New-Item -ItemType Directory -Path $storeDir -Force | Out-Null }
            Install-PSResource PSScriptAnalyzer -Scope CurrentUser -Trust -WarningAction SilentlyContinue
        } else {
            Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Confirm:$false -Scope CurrentUser | Out-Null
            Set-PSRepository -Name PSGallery -InstallationPolicy Trusted
            Install-Module PSScriptAnalyzer -Scope CurrentUser -Force -Confirm:$false -AllowClobber
        }
    }
    # T-260: Invoke-ScriptAnalyzer cannot exclude paths at all (-Path is one
    # non-pipeline string, there is no -Exclude parameter, and the settings
    # file has no ExcludePath), so `-Recurse` from the repo root walks the
    # reactor's churn zones (eclipse/**/target/, npm's node_modules/.bin/*.ps1)
    # and dies mid-run with 'Unable to find the specified file' - exit 1 with
    # NO findings, a false-red gate. Bound the recursion ourselves: enumerate
    # the script file set explicitly and skip exactly the gitignored build
    # outputs (.markdownlint-cli2.yaml already ignores target, node_modules,
    # cpp/build-* for the markdown lane; .gitignore adds __pycache__, .venv,
    # mcp/graphics/data). Excluded paths never reach the analyzer, so build
    # writes/deletes cannot change the result; every real source file is
    # analyzed once, same rules/settings as before - only the file set is
    # bounded.
    $excludeRe = '(^|\\)(target|node_modules|__pycache__|\.venv|\.git)\\'
    # -ErrorAction SilentlyContinue on the walk: an output dir deleted
    # mid-enumeration is the very race being fixed; its entries are excluded
    # anyway, so dropping the walk error is safe (source files are the output).
    $scriptFiles = @(Get-ChildItem -Path $repo -Recurse -File -Include *.ps1, *.psm1, *.psd1 -ErrorAction SilentlyContinue |
        Where-Object {
            $rel = $_.FullName.Substring($repo.Length + 1)
            ($rel -notmatch $excludeRe) -and
            ($rel -notmatch '^cpp\\build[^\\]*\\') -and
            ($rel -notmatch '^mcp\\graphics\\data\\')
        } | Sort-Object FullName)
    $findings = @(foreach ($file in $scriptFiles) { Invoke-ScriptAnalyzer -Path $file.FullName -Settings $settings })
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
