# tasks-tools.ps1 - standalone stdio MCP launcher for the task board (TUI-only use).
#
# Replaces the retired Python `pm` stdio server: it serves the same task_* tools
# as the Eclipse-hosted `eclipse-build` MCP endpoint, but over stdio, so plain
# opencode sessions (no Eclipse running) can use the task store.
#
# Usage (from opencode.json):
#   "mcp": { "tasks": { "type": "local",
#             "command": ["pwsh", "-NoProfile", "-File", "eclipse/tasks-tools.ps1"] } }
#
# Options:
#   -Root <dir>   task store root (default: .opencode/tasks under the current
#                 working directory, i.e. the repository opencode started in)
#
# Requirements: a JDK 21+ (java on PATH or JAVA_HOME), the built tasks+tools
# bundles (mvn package in eclipse/), and gson (resolved from the local Tycho
# p2 cache or the ECLIPSE_HOME install - never a hardcoded path).
param(
    [string]$Root = $(Join-Path (Get-Location) ".opencode/tasks")
)
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# Fleet workers run this script from a WORKTREE checkout (opencode resolves
# the MCP command relative to the session directory), and worktrees have no
# target/ jars - they are gitignored build output. Fall back to the MAIN
# checkout's eclipse/ dir (via git's common-dir) when local jars are absent.
function Resolve-EclipseRoot([string]$scriptDir) {
    $probe = Join-Path $scriptDir "bundles/com.opencode.ide.tools/target/com.opencode.ide.tools-*.jar"
    if (Get-ChildItem $probe -ErrorAction SilentlyContinue) { return $scriptDir }
    try {
        $common = git -C $scriptDir rev-parse --path-format=absolute --git-common-dir 2>$null
        if ($common) {
            $candidate = Join-Path (Split-Path -Parent $common.Trim()) "eclipse"
            if (Get-ChildItem (Join-Path $candidate "bundles/com.opencode.ide.tools/target/com.opencode.ide.tools-*.jar") -ErrorAction SilentlyContinue) {
                return $candidate
            }
        }
    } catch { Write-Verbose "no git common dir for $scriptDir" }
    return $scriptDir
}
$here = Resolve-EclipseRoot $here

# 1) java
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
$java = if ($javaCmd) { $javaCmd.Source } else { $null }
if (-not $java -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME $(if ($IsWindows) { "bin/java.exe" } else { "bin/java" })
    if (Test-Path -LiteralPath $candidate) { $java = $candidate }
}
if (-not $java) { throw "java not found on PATH and JAVA_HOME does not point at a JDK. A JDK 21+ is required." }

# 2) the built bundles (newest jar wins; build with: cd eclipse; .\build.ps1 -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tools clean package)
function Find-BuiltJar([string]$bundle) {
    $jar = Get-ChildItem (Join-Path $here "bundles/$bundle/target/$bundle-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return $jar
}
$tasksJar = Find-BuiltJar "com.opencode.ide.tasks"
$toolsJar = Find-BuiltJar "com.opencode.ide.tools"
if (-not $tasksJar -or -not $toolsJar) {
    # A reactor rebuild wipes target/ wholesale, so a respawn DURING a gate would
    # hard-fail here and strand the MCP status on Error until the next retry.
    # Fall back to the newest B-005-staged copies (7-day retention below) - a
    # slightly older but WORKING server beats no server (U-101 follow-up, live
    # 2026-10-27: exactly this throw fired mid-gate).
    $stagedRoot = Join-Path (Split-Path -Parent $here) ".git/opencode-fleet/lib/tasks-tools"
    $staged = Get-ChildItem $stagedRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($staged) {
        $tasksJar = Get-ChildItem (Join-Path $staged.FullName "com.opencode.ide.tasks-*.jar") -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
        $toolsJar = Get-ChildItem (Join-Path $staged.FullName "com.opencode.ide.tools-*.jar") -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
    }
    if (-not $tasksJar -or -not $toolsJar) {
        throw "Built bundles not found. Run: cd eclipse; .\build.ps1 -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tools clean package"
    }
    Write-Verbose "target jars absent (rebuild in flight?) - using staged copies from $($staged.Name)"
}

# 3) gson: local Tycho p2 cache first, then the Eclipse install ECLIPSE_HOME
#    points at when set (no hardcoded install paths - each machine sets its own).
$gsonCandidates = @()
$gsonCandidates += Get-ChildItem (Join-Path $HOME ".m2/repository/p2/osgi/bundle/com.google.gson/*/com.google.gson-*.jar") -ErrorAction SilentlyContinue
foreach ($install in @($env:ECLIPSE_HOME)) {
    if ($install -and (Test-Path $install)) {
        $gsonCandidates += Get-ChildItem (Join-Path $install "plugins/com.google.gson_*.jar") -ErrorAction SilentlyContinue
    }
}
$gsonJar = $gsonCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $gsonJar) {
    throw "gson jar not found (looked in the Tycho p2 cache ~/.m2/repository/p2/osgi/bundle and `$ECLIPSE_HOME/plugins when set). Run one eclipse build first."
}

# 3b) B-005 jar staging: run the server off a private COPY of the resolved
#     jars (versioned dir, refreshed on every start) so a reactor rebuild
#     that replaces target/ jars underneath a RUNNING server can never rot
#     its classpath (NoClassDefFoundError mid-run, live 2026-09-19). The
#     versioned dir also keeps concurrent servers from locking each other's
#     copies on Windows; old versions are pruned best-effort.
function Publish-ToolClasspath([string[]]$jars, [string]$name) {
    $stageRoot = Join-Path (Split-Path -Parent $here) ".git/opencode-fleet/lib"
    try {
        $stage = Join-Path $stageRoot "$name/$([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))"
        New-Item -ItemType Directory -Path $stage -Force | Out-Null
    } catch {
        $stage = Join-Path ([IO.Path]::GetTempPath()) "opencode-tools-lib/$name/$([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))"
        New-Item -ItemType Directory -Path $stage -Force | Out-Null
    }
    $staged = @()
    foreach ($jar in $jars) {
        $copy = Join-Path $stage (Split-Path -Leaf $jar)
        Copy-Item -LiteralPath $jar -Destination $copy -Force
        $staged += $copy
    }
    $sibling = Split-Path -Parent $stage
    Get-ChildItem $sibling -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-7) } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    return $staged
}

$cp = (Publish-ToolClasspath @($tasksJar.FullName, $toolsJar.FullName, $gsonJar.FullName) "tasks-tools") -join [IO.Path]::PathSeparator
& $java -cp $cp "-Dfile.encoding=UTF-8" com.opencode.ide.tasks.TasksStdioMain --root $Root
# Propagate the JVM's exit code: opencode must see a crashed MCP server as a failure.
exit $LASTEXITCODE
