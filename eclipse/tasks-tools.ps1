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
#   -Root <dir>   task store root (default: .harness/tasks under the current
#                 working directory, i.e. the repository opencode started in)
#
# Requirements: a JDK 21+ (java on PATH or JAVA_HOME), the built tasks+tools
# bundles (mvn package in eclipse/), and gson (resolved from the local Tycho
# p2 cache or the ECLIPSE_HOME install - never a hardcoded path).
#
# Diagnostics + recovery (T-203):
#   Log  : %LOCALAPPDATA%\opencode\logs\tasks-tools-stderr.log (1 MB rotation to .1)
#          carries one block per launch: spawn header, the JVM's stderr, the exit
#          code - and any launcher-level fatal error as a "FATAL" cause line before
#          exit 1. A spawn line with NO exit line means the launcher itself was
#          killed (its per-launch part file keeps the JVM's stderr).
#   Red  : when the endpoint goes red, .harness/tasks-mcp-watchdog.ps1 reconnects it
#          (scheduled every 5 min); by hand: POST /api/experimental/mcp/tasks/connect
#          ?location[directory]=<repo dir>. Health: GET /api/mcp?location[directory]=...
param(
    [string]$Root = $(Join-Path (Get-Location) ".harness/tasks")
)
$ErrorActionPreference = "Stop"

# T-203: without this trap ANY terminating error below gives opencode "MCP server
# process exited with code 1" and NOTHING else - the exact silent-death signature.
# The cause line lands in the persistent stderr log (or a fallback path when the
# error happens before the log plumbing exists), then the launcher exits 1 as before.
trap {
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $cause = "--- fatal $stamp launcherPid=$PID : $($_.Exception.Message) ---"
    $logVar = Get-Variable -Name errLog -ErrorAction SilentlyContinue
    if ($logVar -and $logVar.Value -and (Get-Command Write-ChildErrLog -ErrorAction SilentlyContinue)) {
        Write-ChildErrLog $cause
    } else {
        try {
            $fallbackDir = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "opencode\logs" } else { Join-Path ([IO.Path]::GetTempPath()) "opencode" }
            $fallbackLog = Join-Path $fallbackDir "tasks-tools-stderr.log"
            if (-not (Test-Path -LiteralPath $fallbackDir)) { New-Item -ItemType Directory -Path $fallbackDir -Force | Out-Null }
            Add-Content -LiteralPath $fallbackLog -Value $cause -Encoding utf8
        } catch { Write-Verbose "fatal cause write failed: $($_.Exception.Message)" }
    }
    exit 1
}

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

# 2) the server jars. B-027 resolution order (gate-stable first):
#    1. DEPLOYED DROPINS ($env:ECLIPSE_HOME/dropins/...) - refreshed at every
#       deploy, never touched by gate rebuilds; a live server SURVIVES gates.
#    2. Reactor targets (newest dev build) - volatile: gates wipe them, which
#       is what kills respawns mid-arc (B-027).
#    3. Staged copies (.git/opencode-fleet/lib) - last resort.
#    Dev builds reach the server by REDEPLOYING (deploy-dev.ps1) - the store
#    protocol changes rarely, and the ticket FILES are the interface.
# B-027 follow-up: when ECLIPSE_HOME is unset (sessions started before it was
# configured), default to the same machine convention deploy-dev.ps1 uses
# (C:\eclipse-cpp) so gate-wipe respawns still resolve the stable dropins.
$eclipseHome = if ($env:ECLIPSE_HOME) { $env:ECLIPSE_HOME } else { 'C:\eclipse-cpp' }
function Find-ServerJar([string]$bundle) {
    if ($eclipseHome) {
        $jar = Get-ChildItem (Join-Path $eclipseHome "dropins/opencode-ide/plugins/$bundle-*.jar") -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($jar) { return $jar }
    }
    $jar = Get-ChildItem (Join-Path $here "bundles/$bundle/target/$bundle-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($jar) { return $jar }
    $stagedRoot = Join-Path (Split-Path -Parent $here) ".git/opencode-fleet/lib/tasks-tools"
    $staged = Get-ChildItem $stagedRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($staged) {
        $jar = Get-ChildItem (Join-Path $staged.FullName "$bundle-*.jar") -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
        if ($jar) { return $jar }
    }
    return $null
}
$tasksJar = Find-ServerJar "com.opencode.ide.tasks"
$toolsJar = Find-ServerJar "com.opencode.ide.tools"
if (-not $tasksJar -or -not $toolsJar) {
    throw "Server jars not found (looked in: dropins under ECLIPSE_HOME, reactor targets, staged copies). Run: cd eclipse; .\build.ps1 -pl bundles/com.opencode.ide.tasks -pl bundles/com.opencode.ide.tools clean package; then deploy-dev.ps1"
}
Write-Verbose "tasks jar: $($tasksJar.FullName); tools jar: $($toolsJar.FullName)"

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
# stderr of the JVM is captured to a PERSISTENT shared log: MCP protocol runs on
# stdout, so this cannot disturb the wire; it gives us the dying words when the
# child exits (T-203: the recurring silent deaths - exit 255/4/1, 2026-11-19/23 -
# left NO child stderr behind: the old per-PID file under $env:TEMP was neither
# durable nor shared).
#
# Layout - $errLog ( %LOCALAPPDATA%\opencode\logs\tasks-tools-stderr.log, rotated
# at ~1 MB to .1 ), one block per launch, appended with an EXCLUSIVE open + retry
# so two concurrent launchers (main checkout + a worktree session) can neither
# crash each other nor clobber each other's lines:
#   "--- spawn <ts> launcherPid=<pid> root=<root> part=<partLog> ---"
#   <the JVM's stderr, merged from the per-launch part file on termination>
#   "--- exit <code> launcherPid=<pid> <ts> ---"      <- written on termination
# While the child RUNS its stderr sits in $partLog (a per-launch file - the
# `2>>` redirect needs an exclusive open, which is exactly what two instances
# sharing one file crashed on: exit 1, nothing captured - T-203 repro 2026-11-23).
# Diagnosis rule: a spawn line with NO matching exit line means the LAUNCHER was
# killed (the part file keeps the JVM's stderr); an exit line names the JVM's code.
$errDir = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "opencode\logs" } else { Join-Path ([IO.Path]::GetTempPath()) "opencode" }
$errLog = Join-Path $errDir "tasks-tools-stderr.log"
$partLog = Join-Path $errDir ("tasks-tools-stderr.{0}-{1}.part.log" -f (Get-Date -Format 'yyyyMMddHHmmssfff'), $PID)
function Write-ChildErrLog([string]$line) {
    Write-SharedLogContent ([Text.Encoding]::UTF8.GetBytes($line + "`n"))
}
function Write-SharedLogContent([byte[]]$bytes) {
    # best-effort: diagnostics must never take the MCP server down with them
    try {
        if (-not (Test-Path -LiteralPath $errDir)) { New-Item -ItemType Directory -Path $errDir -Force | Out-Null }
        # rotate at ~1 MB: keep the current file small, one retired copy carries the tail
        # (best-effort: a rename can fail while another launcher holds the file open)
        $f = Get-Item -LiteralPath $errLog -ErrorAction SilentlyContinue
        if ($f -and $f.Length -gt 1MB) { Move-Item -LiteralPath $errLog -Destination "$errLog.1" -Force -ErrorAction SilentlyContinue }
        # EXCLUSIVE open + retry: two launchers appending at the same end offset used to
        # clobber one another's line (FileShare.ReadWrite lost a spawn header - T-203 repro
        # 2026-11-23). Exclusivity makes each append atomic; the retry rides out the peer.
        $fs = $null
        for ($i = 1; $i -le 6 -and -not $fs; $i++) {
            try { $fs = [IO.File]::Open($errLog, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None) }
            catch { if ($i -ge 6) { throw } else { Start-Sleep -Milliseconds (25 * $i) } }
        }
        try { $fs.Write($bytes, 0, $bytes.Length) } finally { $fs.Dispose() }
    } catch { Write-Verbose "stderr log write failed: $($_.Exception.Message)" }
}
function Merge-ChildErrPart {
    # move the per-launch JVM stderr into the shared log, then drop the part file
    try {
        if (-not (Test-Path -LiteralPath $partLog)) { return }
        $bytes = [IO.File]::ReadAllBytes($partLog)
        if ($bytes.Length -gt 0) { Write-SharedLogContent $bytes }
        Remove-Item -LiteralPath $partLog -Force -ErrorAction SilentlyContinue
    } catch { Write-Verbose "stderr part merge failed: $($_.Exception.Message)" }
}
try { if (-not (Test-Path -LiteralPath $errDir)) { New-Item -ItemType Directory -Path $errDir -Force | Out-Null } } catch { Write-Verbose "stderr log dir create failed: $($_.Exception.Message)" }
# prune stale part files a killed launcher left behind (older than a day).
# Match the REAL name `tasks-tools-stderr.<stamp>-<pid>.part.log` (DOT after
# "stderr", see $partLog) - the old DASH glob matched nothing.
try { Get-ChildItem -LiteralPath $errDir -Filter 'tasks-tools-stderr.*.part.log' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } | Remove-Item -Force -ErrorAction SilentlyContinue } catch { Write-Verbose "stale part prune failed: $($_.Exception.Message)" }
Write-ChildErrLog "--- spawn $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') launcherPid=$PID root=$Root part=$partLog ---"
& $java -cp $cp "-Dfile.encoding=UTF-8" com.opencode.ide.tasks.TasksStdioMain --root $Root 2>> $partLog
# Propagate the JVM's exit code: opencode must see a crashed MCP server as a failure.
$childExit = $LASTEXITCODE
Merge-ChildErrPart
Write-ChildErrLog "--- exit $childExit launcherPid=$PID $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ---"
exit $childExit
