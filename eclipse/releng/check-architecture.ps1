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
#   4. no machine-specific absolute paths in sources (the no-fixed-paths rule;
#      layer 2b extends it to *.ini anywhere under eclipse/, B-019);
#   5. no mojibake (double-encoded UTF-8) in the harness sources and the repo
#      docs - the compiler accepts it, users read it.

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
    Where-Object { $_.FullName -notmatch '[\\/]target[\\/]' -and $_.FullName -notmatch '\.tests[\\/]' } |
    ForEach-Object {
        $hit = Select-String -Path $_.FullName -Pattern $pattern -SimpleMatch:$false | Select-Object -First 1
        if ($hit) {
            $violations += "machine-specific path in $($_.FullName):$($hit.LineNumber) - resolve at runtime instead"
        }
    }

# layer 2b: the same no-fixed-paths rule over *.ini files anywhere under
# eclipse/ (B-019: the shipped plugin_customization.ini used to carry the
# build machine's repo paths). Ini files escape the drive colon (C\:/...) on
# Windows and commonly use forward slashes, so this pattern allows both
# slash directions AND the escaped-colon form; build output (target/) and
# vendored min.* files are excluded like everywhere else.
$iniPattern = '[A-Za-z]\\?:[\\/]{1,2}(Users|Development)[\\/]'
Get-ChildItem $root -Recurse -Include *.ini -File |
    Where-Object { $_.FullName -notmatch '[\\/]target[\\/]' -and $_.FullName -notmatch '[\\/]node_modules[\\/]' -and $_.Name -notmatch '\.min\.' } |
    ForEach-Object {
        $hit = Select-String -Path $_.FullName -Pattern $iniPattern -SimpleMatch:$false | Select-Object -First 1
        if ($hit) {
            $violations += "machine-specific path in $($_.FullName):$($hit.LineNumber) - resolve at runtime instead"
        }
    }

# layer 3: no mojibake. A UTF-8 file that takes an ANSI round-trip (a Windows
# PowerShell 5.1 Get-Content/Set-Content edit) turns every non-ASCII character
# into two or three cp1252 characters - an em dash becomes U+00E2 U+20AC
# U+201D. Detected: U+00C2/U+00C3 followed by a cp1252-decoded continuation
# byte, or U+00E2 followed by two. Vendored third-party bundles (hljs/,
# katex/, *.min.*) are out of scope; intentional fixtures use \u escapes.
$repo = Split-Path -Parent $root
$cont = '[\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178\u017D\u017E\u0192\u02C6\u02DC\u2013\u2014' +
        '\u2018\u2019\u201A\u201C\u201D\u201E\u2020\u2021\u2022\u2026\u2030\u2039\u203A\u20AC\u2122]'
$mojibake = [regex]('[\u00C2\u00C3]' + $cont + '|\u00E2' + $cont + $cont)
$scanRoots = @((Join-Path $root 'bundles'), (Join-Path $root 'components'), (Join-Path $root 'mojo'),
    (Join-Path $root 'releng'), (Join-Path $repo 'docs'), (Join-Path $repo '.opencode/skills'),
    (Join-Path $repo '.opencode/agent')) | Where-Object { Test-Path $_ }
$textFiles = @(Get-ChildItem $scanRoots -Recurse -File -Include *.java, *.js, *.mjs, *.html, *.css, *.xml,
        *.properties, *.MF, *.md, *.json, *.ps1) +
    @(Get-ChildItem $root, $repo, (Join-Path $repo 'cpp') -File -Filter *.md -ErrorAction SilentlyContinue)
$textFiles |
    Where-Object { $_.FullName -notmatch '[\\/](target|node_modules|hljs|katex)[\\/]' -and $_.Name -notmatch '\.min\.' } |
    ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName, [Text.Encoding]::UTF8)
        $hit = $mojibake.Match($text)
        if ($hit.Success) {
            $line = ($text.Substring(0, $hit.Index) -split "`n").Count
            $violations += "mojibake (double-encoded UTF-8) in $($_.FullName):$line - re-save the text as UTF-8"
        }
    }

if ($violations.Count -gt 0) {
    Write-Host 'architecture check FAILED:'
    $violations | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'architecture check passed (dependency direction + no fixed paths + no mojibake)'
