# fleet-daemon.ps1 - detached fleet daemon launcher (V-006): starts the
# persistent per-repo fleet engine headlessly, so bash/pwsh (no Eclipse, no
# opencode session) can bring the daemon up; MCP stdio sessions then attach
# to it by setting FLEET_DAEMON=auto|always (see FleetStdioMain /
# FleetDaemonProxy - the stdio server becomes a thin proxy instead of
# spawning its own engine).
#
# Usage:
#   .\fleet-daemon.ps1                          # daemon for the repo under the current directory
#   .\fleet-daemon.ps1 -Repo C:\path\to\repo
#   $env:FLEET_DAEMON_PASSWORD = "..." ; .\fleet-daemon.ps1   # pin the daemon token
#
# What it does:
#   - resolves java + the built fleet/client/git/tasks/tools jars + gson
#     (same resolution as fleet-tools.ps1),
#   - derives the task store root <repo>\.opencode\tasks (the --root form
#     FleetDaemonMain expects, same convention as fleet-tools.ps1) and the
#     fleet coordination dir <repo>\.git\opencode-fleet (linked-worktree
#     aware, like FleetGit.fleetRoot),
#   - refuses when the pidfile names a live daemon (pid alive AND port
#     answering, like DaemonProwl; the daemon-side start guard remains
#     authoritative),
#   - starts com.opencode.ide.fleet.FleetDaemonMain DETACHED (Start-Process,
#     no -Wait) with stdout AND stderr appended to
#     <repo>\.git\opencode-fleet\daemon.log (a hidden pwsh wrapper runs the
#     append - Start-Process itself can only truncate),
#   - waits (bounded, 20s) for the daemon to publish its pidfile (pid, port,
#     token, startedAt) and prints pid + port when it did.
#
# Stopping is the graceful daemon/shutdown request of the daemon protocol
# (a fleet_daemon_shutdown tool / a stop subcommand in a later slice); the
# documented last-resort fallback is `taskkill /PID <pid> /T` (Windows) /
# `kill <pid>` otherwise - mid-run jobs then settle via the store's
# crash-recovery path.
#
# Requirements: a JDK 21+ (java on PATH or JAVA_HOME; the bundles are
# JavaSE-21), the built fleet+client+git+tasks+tools bundles (mvn package in
# eclipse/), and gson (resolved from the local Tycho p2 cache or an Eclipse
# install).
param(
    [string]$Repo = (Get-Location).Path
)
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# 0) repository root, task store root, and the fleet coordination directory
#    (.git may be a linked-worktree pointer)
$Repo = [IO.Path]::GetFullPath($Repo)
if (-not (Test-Path -LiteralPath (Join-Path $Repo ".git"))) {
    throw "not a git repository (no .git under $Repo); pass -Repo <repoRoot>"
}
$gitDir = Join-Path $Repo ".git"
if (Test-Path -LiteralPath $gitDir -PathType Leaf) {
    # linked worktree: follow the gitdir pointer, then commondir - like FleetGit.fleetRoot
    $pointer = (Get-Content -LiteralPath $gitDir -Raw).Trim()
    if (-not $pointer.StartsWith("gitdir:")) { throw "invalid git directory pointer: $gitDir" }
    $gitDir = [IO.Path]::GetFullPath((Join-Path $Repo $pointer.Substring(8).Trim()))
    $common = Join-Path $gitDir "commondir"
    if (Test-Path -LiteralPath $common -PathType Leaf) {
        $gitDir = [IO.Path]::GetFullPath((Join-Path $gitDir (Get-Content -LiteralPath $common -Raw).Trim()))
    }
}
$fleetDir = Join-Path $gitDir "opencode-fleet"
New-Item -ItemType Directory -Force -Path $fleetDir | Out-Null
$pidfile = Join-Path $fleetDir "daemon.json"
# FleetToolProvider/FleetControl expect the STORE root; repoRootOf strips
# exactly the two segments below it back to $Repo.
$storeRoot = Join-Path $Repo (Join-Path ".opencode" "tasks")

# refuse to double-start: like DaemonProwl, live means pid alive AND the
# port answering one TCP connect. (The daemon-side start guard is
# authoritative; this is the headless-friendly pre-check.)
function Test-PortAnswering([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        try { return ($client.ConnectAsync("127.0.0.1", $Port).Wait(300) -and $client.Connected) }
        catch { return $false } # actively refused / faulted connect: nothing listens
    } finally { $client.Dispose() }
}
if (Test-Path -LiteralPath $pidfile) {
    $existing = $null
    try { $existing = Get-Content -LiteralPath $pidfile -Raw | ConvertFrom-Json } catch { $existing = $null }
    if ($existing -and $existing.pid -and (Get-Process -Id $existing.pid -ErrorAction SilentlyContinue)) {
        if ($existing.port -and (Test-PortAnswering ([int]$existing.port))) {
            throw "a fleet daemon already appears to be running for $Repo (pid $($existing.pid), port $($existing.port)); stop it before starting another"
        }
        Write-Warning "pidfile names pid $($existing.pid) but nothing answers on port $($existing.port); treating it as stale (the daemon overwrites it)"
    }
}

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

# 3) gson: local Tycho p2 cache first, then Eclipse installs
$gsonCandidates = @()
$gsonCandidates += Get-ChildItem (Join-Path $HOME ".m2/repository/p2/osgi/bundle/com.google.gson/*/com.google.gson-*.jar") -ErrorAction SilentlyContinue
foreach ($install in @($env:ECLIPSE_HOME, $(if ($IsWindows) { "C:\eclipse-cpp" }))) {
    if ($install -and (Test-Path $install)) {
        $gsonCandidates += Get-ChildItem (Join-Path $install "plugins/com.google.gson_*.jar") -ErrorAction SilentlyContinue
    }
}
$gsonJar = $gsonCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $gsonJar) {
    throw "gson jar not found (looked in the Tycho p2 cache ~/.m2/repository/p2/osgi/bundle and Eclipse plugins/). Run one eclipse build first."
}

# 4) start the daemon DETACHED, both output streams APPENDED to one log
#    file. Start-Process -RedirectStandard* only truncates, so a hidden pwsh
#    wrapper runs the JVM with `*>>`; the paths travel via environment
#    variables, which no quoting (spaces, apostrophes) can break.
$cp = ($fleetJar.FullName, $clientJar.FullName, $gitJar.FullName, $tasksJar.FullName, $toolsJar.FullName, $gsonJar.FullName) -join [IO.Path]::PathSeparator
$log = Join-Path $fleetDir "daemon.log"
$env:FLEET_LAUNCH_JAVA = $java
$env:FLEET_LAUNCH_CP   = $cp
$env:FLEET_LAUNCH_ROOT = $storeRoot
$env:FLEET_LAUNCH_LOG  = $log
# The -Dfile.encoding=UTF-8 argument MUST stay quoted inside the wrapper:
# pwsh's native-argument parsing splits a bare -Dfile.encoding=UTF-8 token
# into "-Dfile" + ".encoding=UTF-8" (verified), and java would then treat
# the fragment as the main class. Doubled single quotes keep the wrapper
# free of double quotes, so the outer -Command quoting below stays safe.
$wrapper = '& $env:FLEET_LAUNCH_JAVA -cp $env:FLEET_LAUNCH_CP ''-Dfile.encoding=UTF-8'' com.opencode.ide.fleet.FleetDaemonMain --root $env:FLEET_LAUNCH_ROOT *>> $env:FLEET_LAUNCH_LOG; exit $LASTEXITCODE'
$pwsh = (Get-Process -Id $PID).Path
# Start-Process joins -ArgumentList with spaces WITHOUT quoting (verified on
# PS 7.6): on Windows the one argument containing spaces must carry its own
# quotes; on Unix arguments are exec'd directly and must stay unquoted.
if ($IsWindows) {
    $process = Start-Process -FilePath $pwsh -ArgumentList @("-NoProfile", "-Command", ('"' + $wrapper + '"')) -WindowStyle Hidden -PassThru
} else {
    $process = Start-Process -FilePath $pwsh -ArgumentList @("-NoProfile", "-Command", $wrapper) -PassThru
}

# 5) wait (bounded) for the daemon to register - it writes daemon.json once
#    its port is bound. A stale pidfile from a dead daemon must not satisfy
#    the wait, so require a NEW file or one rewritten after the launch.
$before = if (Test-Path -LiteralPath $pidfile) { (Get-Item -LiteralPath $pidfile).LastWriteTimeUtc } else { $null }
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $pidfile) {
        $written = (Get-Item -LiteralPath $pidfile).LastWriteTimeUtc
        if ($null -eq $before -or $written -gt $before) { break }
    }
    Start-Sleep -Milliseconds 250
}
if (Test-Path -LiteralPath $pidfile) {
    $registered = $null
    try { $registered = Get-Content -LiteralPath $pidfile -Raw | ConvertFrom-Json } catch { $registered = $null }
    if ($registered -and $registered.port) {
        Write-Host "fleet daemon started: pid $($registered.pid), port $($registered.port) (log: $log)"
    } else {
        Write-Host "fleet daemon process started (wrapper pid $($process.Id)); pidfile $pidfile is not readable yet"
    }
} else {
    Write-Warning "fleet daemon (wrapper pid $($process.Id)) did not register within 20s; inspect $log"
}
