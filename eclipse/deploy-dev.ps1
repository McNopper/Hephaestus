# deploy-dev.ps1 - fast development loop: copies the freshly built plugin JARs
# into the Eclipse CDT dropins folder, so you can test changes without building
# a p2 repo and without PDE installed.
#
# Usage:
#   .\build.ps1 clean verify     # build first
#   .\deploy-dev.ps1             # copy JARs to dropins
#   # then restart the Eclipse install (default C:\eclipse-cpp; -EclipseRoot / ECLIPSE_HOME to override)
param([string]$EclipseRoot = $(if ($env:ECLIPSE_HOME) { $env:ECLIPSE_HOME } else { "C:\eclipse-cpp" }))
$ErrorActionPreference = "Stop"

$root      = $PSScriptRoot
$bundleDir = Join-Path $root "bundles"
$dropins   = Join-Path $EclipseRoot "dropins\opencode-ide"
$plugins   = Join-Path $dropins "plugins"

if (-not (Test-Path -LiteralPath (Join-Path $EclipseRoot "dropins"))) {
    throw "$EclipseRoot\dropins not found. Is Eclipse CDT installed there? (default C:\eclipse-cpp; pass -EclipseRoot or set ECLIPSE_HOME to override)"
}

# wipe the whole opencode-ide dropin so p2 never sees stale versions/layouts
if (Test-Path -LiteralPath $dropins) {
    Remove-Item -LiteralPath $dropins -Recurse -Force
}
New-Item -ItemType Directory -Path $plugins -Force | Out-Null

# Clear the cached OSGi state: it still references the previous build's
# qualifiers after a redeploy, which p2 reports as "another singleton bundle
# selected" conflicts. This path is regenerated on the next start (the same
# effect as launching with -clean).
# NEVER delete org.eclipse.equinox.simpleconfigurator\bundles.info here: it is
# the bootstrap list of bundles to start - without it the framework cannot even
# launch the code that would rebuild it, and the install only recovers by
# renaming away the whole configuration directory.
$osgiCache = Join-Path (Join-Path $EclipseRoot "configuration") "org.eclipse.osgi"
if (Test-Path -LiteralPath $osgiCache) {
    try {
        Remove-Item -LiteralPath $osgiCache -Recurse -Force
        Write-Host "[deploy-dev] cleared $osgiCache" -ForegroundColor DarkGray
    } catch {
        Write-Warning "could not clear $osgiCache (Eclipse running?). Close Eclipse and redeploy, or launch once with -clean."
    }
}

# p2 recognizes a dropin subfolder that has a plugins/ (and optional features/) layout.
# Derived from the source tree rather than hardcoded, so a newly added bundle is
# never silently left undeployed. Test fragments are not runtime plugins.
$bundles = Get-ChildItem -LiteralPath $bundleDir -Directory |
    Where-Object { $_.Name -notlike "*.tests" } |
    Select-Object -ExpandProperty Name |
    Sort-Object
if (-not $bundles) {
    throw "No bundles found under $bundleDir."
}
foreach ($b in $bundles) {
    $jar = Get-ChildItem (Join-Path $bundleDir "$b\target\$b-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        throw "No built JAR found for $b. Run .\build.ps1 clean verify first."
    }
    Copy-Item -LiteralPath $jar.FullName -Destination $plugins -Force
    Write-Host "[deploy-dev] $($jar.Name) -> $plugins" -ForegroundColor Green
}

# If bundles.info carries manual opencode-ide dropin lines (the recovery state
# after a damaged p2 profile, where the dropins reconciler no longer manages
# them), refresh those lines to the freshly built versions so OSGi never sees a
# manifest/bundles.info version mismatch. Normal installs rely on the p2
# reconciler and simply have no such lines - then this is a no-op.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$bundlesInfo = Join-Path $EclipseRoot "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
if (Test-Path -LiteralPath $bundlesInfo) {
    $lines = [System.Collections.Generic.List[string]](Get-Content -LiteralPath $bundlesInfo)
    # match with or without the "file:" prefix - the p2 reconciler rewrites dropin
    # lines in its own relative form and drops the prefix, and a prefix-only match
    # would silently skip the refresh (stale qualifiers then break bundle install)
    $existing = @($lines | Where-Object { $_ -match ',file:dropins/opencode-ide/plugins/|,dropins/opencode-ide/plugins/' })
    if ($existing.Count -gt 0) {
        foreach ($jar in Get-ChildItem $plugins -Filter "*.jar") {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
            try {
                $entry = $zip.GetEntry("META-INF/MANIFEST.MF")
                $reader = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8)
                $raw = $reader.ReadToEnd(); $reader.Close()
                $unfolded = ($raw -replace "`r?`n ", "") -replace "`r?`n", "`n"
                $id = $null; $ver = $null
                foreach ($line in ($unfolded -split "`n")) {
                    if ($line -match '^Bundle-SymbolicName:\s*([^;\r\n]+)') { $id = $Matches[1].Trim() }
                    elseif ($line -match '^Bundle-Version:\s*(\S+)') { $ver = $Matches[1].Trim() }
                }
            } finally { $zip.Dispose() }
            if ($id -and $ver) {
                $newLine = "$id,$ver,file:dropins/opencode-ide/plugins/$($jar.Name),4,false"
                $replaced = $false
                for ($i = 0; $i -lt $lines.Count; $i++) {
                    if ($lines[$i] -match "^$id,.*dropins/opencode-ide/plugins/") {
                        $lines[$i] = $newLine; $replaced = $true; break
                    }
                }
                if (-not $replaced) { $lines.Add($newLine) }
            }
        }
        Set-Content -LiteralPath $bundlesInfo -Value $lines
        Write-Host "[deploy-dev] refreshed opencode-ide lines in bundles.info"

# --- harness defaults: plugin_customization.ini + the eclipse.ini flag ---
$customization = Join-Path $PSScriptRoot "plugin_customization.ini"
if (Test-Path $customization) {
    Copy-Item $customization $EclipseRoot -Force
    Write-Host "[deploy-dev] plugin_customization.ini -> $EclipseRoot"
    $iniPath = Join-Path $EclipseRoot "eclipse.ini"
    if (Test-Path $iniPath) {
        $ini = Get-Content $iniPath
        if (-not ($ini | Select-String -SimpleMatch "-pluginCustomization")) {
            # same rule as the -clean hint: one option per line near the top
            $at = 0
            for ($i = 0; $i -lt $ini.Count; $i++) {
                if ($ini[$i] -match "^-vmargs") { $at = $i; break }
            }
            $patched = $ini[0..($at - 1)] + @("-pluginCustomization", "plugin_customization.ini") + $ini[$at..($ini.Count - 1)]
            Set-Content -Path $iniPath -Value $patched
            Write-Host "[deploy-dev] eclipse.ini: added -pluginCustomization plugin_customization.ini"
        }
    }
} -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "Done. (Re)start $EclipseRoot to load the plugins." -ForegroundColor Cyan
Write-Host "If views/perspective don't update, run eclipse once with -clean" -ForegroundColor DarkGray
Write-Host "(add '-clean' on its own line near the top of $EclipseRoot\eclipse.ini, then remove it after one launch)." -ForegroundColor DarkGray
