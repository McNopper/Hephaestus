# fleet-tools.ps1 - standalone stdio MCP launcher for the task fleet (chat-first control).
#
# Serves the fleet_* tools over stdio so plain opencode sessions (no Eclipse
# running) can dispatch the fleet, poll jobs, and sync the store. Modeled on
# tasks-tools.ps1; the spawned engine starts its own `opencode serve` on first
# dispatch and kills it on shutdown.
#
# Usage (from opencode.json):
#   "mcp": { "fleet": { "type": "local",
#             "command": ["pwsh", "-NoProfile", "-File", "eclipse/fleet-tools.ps1"] } }
#
# Options:
#   -Root <dir>   task store root (default: .opencode/tasks under the current
#                 working directory, i.e. the repository opencode started in)
#
# The launched server honors the FLEET_DAEMON environment variable
# (off|auto|always; see FleetStdioMain): off/default owns a local engine in
# this process; auto/always attach it as a thin proxy to the detached fleet
# daemon started by eclipse/fleet-daemon.ps1 instead of spawning another
# engine. The variable is inherited from the environment - set it in the
# launching shell or per MCP server in the opencode config.
#
# Requirements: a JDK 21+ (java on PATH or JAVA_HOME; the bundles are
# JavaSE-21), the built fleet+client+git+tasks+tools bundles (mvn package in
# eclipse/), and gson (resolved from the local Tycho p2 cache or the
# ECLIPSE_HOME install - never a hardcoded path).
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
    } catch { }
    return $scriptDir
}
$here = Resolve-EclipseRoot $here

# 1) java
$java = (Get-Command java -ErrorAction SilentlyContinue)?.Source
if (-not $java -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME $(if ($IsWindows) { "bin/java.exe" } else { "bin/java" })
    if (Test-Path -LiteralPath $candidate) { $java = $candidate }
}
if (-not $java) { throw "java not found on PATH and JAVA_HOME does not point at a JDK. A JDK 21+ is required." }

# 2) the built bundles (newest jar wins; build with: cd eclipse; .\build.ps1 -pl bundles/com.opencode.ide.fleet -pl bundles/com.opencode.ide.client -pl bundles/com.opencode.ide.git -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tools clean package)
function Find-BuiltJar([string]$bundle) {
    $jar = Get-ChildItem (Join-Path $here "bundles/$bundle/target/$bundle-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return $jar
}
$fleetJar  = Find-BuiltJar "com.opencode.ide.fleet"
$clientJar = Find-BuiltJar "com.opencode.ide.client"
$gitJar    = Find-BuiltJar "com.opencode.ide.git"
$tasksJar  = Find-BuiltJar "com.opencode.ide.tasks"
$toolsJar  = Find-BuiltJar "com.opencode.ide.tools"
$missing = @($fleetJar, $clientJar, $gitJar, $tasksJar, $toolsJar) | Where-Object { -not $_ }
if ($missing.Count -gt 0) {
    throw "Built bundles not found. Run: cd eclipse; .\build.ps1 -pl bundles/com.opencode.ide.fleet -pl bundles/com.opencode.ide.client -pl bundles/com.opencode.ide.git -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tools clean package"
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

$cp = ($fleetJar.FullName, $clientJar.FullName, $gitJar.FullName, $tasksJar.FullName, $toolsJar.FullName, $gsonJar.FullName) -join [IO.Path]::PathSeparator
& $java -cp $cp "-Dfile.encoding=UTF-8" com.opencode.ide.fleet.FleetStdioMain --root $Root
# Propagate the JVM's exit code: opencode must see a crashed MCP server as a failure.
exit $LASTEXITCODE
