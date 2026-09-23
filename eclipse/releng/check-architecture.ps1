# check-architecture.ps1 - clean-architecture conformance (see QUALITY.md).
# Companion to the per-bundle `ban-eclipse-imports` exec checks: those keep the
# Eclipse-free bundles pure; THIS checks the DEPENDENCY DIRECTION between the
# harness bundles and the repo's fixed-path rule. Runs once per reactor from
# the parent pom; fails the build on any violation.
#
# Rules (from the panel-IA verdict 2026-09-23):
#   1. the platform-free layer (client/tools/tasks/git/fleet) and core must not
#      depend on the presentation bundles (ui/chat/board) - the dependency
#      arrow only ever points UP the layer cake. (The layer's one allowed
#      platform dependency is the JobManager runtime, org.eclipse.core.jobs +
#      org.eclipse.equinox.common - both plain-JVM-safe, per the 2026-09-23
#      "we do not reinvent everything from scratch" direction; tools/tasks
#      keep strict `ban-eclipse-imports` purity.);
#   2. chat must not depend on ui or board; ui must not depend on chat or board;
#   3. nothing depends on a *.tests bundle;
#   4. no machine-specific absolute paths in sources (the no-fixed-paths rule).

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot   # eclipse/
$violations = @()

# layer 1: dependency direction from every MANIFEST's Require-Bundle list
$forbidden = @{
    'com.opencode.ide.client' = @('com.opencode.ide.ui', 'com.opencode.ide.chat', 'com.opencode.ide.board')
    'com.opencode.ide.tools'  = @('com.opencode.ide.ui', 'com.opencode.ide.chat', 'com.opencode.ide.board')
    'com.opencode.ide.tasks'  = @('com.opencode.ide.ui', 'com.opencode.ide.chat', 'com.opencode.ide.board')
    'com.opencode.ide.git'    = @('com.opencode.ide.ui', 'com.opencode.ide.chat', 'com.opencode.ide.board')
    'com.opencode.ide.fleet'  = @('com.opencode.ide.ui', 'com.opencode.ide.chat', 'com.opencode.ide.board')
    'com.opencode.ide.core'   = @('com.opencode.ide.ui', 'com.opencode.ide.chat', 'com.opencode.ide.board')
    'com.opencode.ide.chat'   = @('com.opencode.ide.ui', 'com.opencode.ide.board')
    'com.opencode.ide.ui'     = @('com.opencode.ide.chat', 'com.opencode.ide.board')
}

Get-ChildItem (Join-Path $root 'bundles') -Directory | ForEach-Object {
    $manifest = Join-Path $_.FullName 'META-INF\MANIFEST.MF'
    if (-not (Test-Path $manifest)) { return }
    $bundle = $_.Name
    $text = Get-Content $manifest -Raw
    # continuation lines fold with a leading space; unfold before matching
    $unfolded = $text -replace "`r?`n ", ''
    foreach ($layer in $forbidden.Keys) {
        if ($bundle -ne $layer) { continue }
        foreach ($banned in $forbidden[$layer]) {
            if ($unfolded -match [regex]::Escape($banned)) {
                $violations += "$bundle must not depend on $banned (dependency arrow points up the layer cake)"
            }
        }
    }
    if ($bundle -like '*.tests' -and $unfolded -match 'Require-Bundle:.*com\.opencode\.ide\.[a-z]+\.tests') {
        $violations += "$bundle must not depend on another tests bundle"
    }
}

# layer 2: the no-fixed-paths rule over all tracked sources. Test PARSE
# FIXTURES with captured path strings are the one accepted exception
# (QUALITY.md) - production bundles and the mojo are the rule's target.
$pattern = '[A-Za-z]:\\\\(Users|Development)\\\\'
Get-ChildItem (Join-Path $root 'bundles'), (Join-Path $root 'mojo') -Recurse -Include *.java, *.xml, *.properties -File |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -notmatch '\.tests\\' } |
    ForEach-Object {
        $hit = Select-String -Path $_.FullName -Pattern $pattern -SimpleMatch:$false | Select-Object -First 1
        if ($hit) {
            $violations += "machine-specific path in $($_.FullName):$($hit.LineNumber) - resolve at runtime instead"
        }
    }

if ($violations.Count -gt 0) {
    Write-Host 'architecture check FAILED:'
    $violations | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'architecture check passed (dependency direction + no fixed paths)'
