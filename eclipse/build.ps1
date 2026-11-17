# build.ps1 - thin wrapper around the Maven Wrapper that ensures a valid JAVA_HOME.
# The system JAVA_HOME may be missing or point to a non-existent JRE; this resolves
# a real JDK before invoking mvnw (mvnw.cmd on Windows, mvnw on unix).
# All arguments are forwarded to Maven, e.g.:  .\build.ps1 clean verify
$ErrorActionPreference = "Stop"

$IsWindowsOS = $env:OS -eq "Windows_NT" -or [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)
$JavaBin = if ($IsWindowsOS) { "java.exe" } else { "java" }

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path (Join-Path $env:JAVA_HOME "bin") $JavaBin))) { return $env:JAVA_HOME.Trim() }
    $result = $null

    # Windows: registry first, then common install dirs
    if ($IsWindowsOS) {
        foreach ($key in @("HKLM:\SOFTWARE\JavaSoft\JDK", "HKLM:\SOFTWARE\JavaSoft\Java Development Kit")) {
            if ($result) { break }
            if (-not (Test-Path $key)) { continue }
            try {
                foreach ($k in (Get-ChildItem $key -ErrorAction Stop)) {
                    $javaHome = (Get-ItemProperty $k.PSPath -Name JavaHome -ErrorAction SilentlyContinue).JavaHome
                    if ($javaHome -and (Test-Path "$javaHome\bin\java.exe")) { $result = $javaHome; break }
                }
            } catch {
                Write-Warning "[build] JDK registry lookup failed for ${key}: $($_.Exception.Message)"
            }
        }
        if (-not $result) {
            foreach ($glob in @("C:\Program Files\Java\jdk-*", "C:\Program Files\Eclipse Adoptium\jdk-*", "C:\Program Files\Microsoft\jdk-*")) {
                if ($result) { break }
                foreach ($m in (Get-Item $glob -ErrorAction SilentlyContinue)) {
                    if (Test-Path "$($m.FullName)\bin\java.exe") { $result = $m.FullName; break }
                }
            }
        }
    } else {
        # Unix: common install locations
        foreach ($glob in @("/usr/lib/jvm/java-2*-openjdk-*", "/usr/lib/jvm/temurin-*", "/opt/java/*")) {
            if ($result) { break }
            foreach ($m in (Get-Item $glob -ErrorAction SilentlyContinue)) {
                if (Test-Path (Join-Path -Path (Join-Path -Path $m.FullName -ChildPath "bin") -ChildPath "java")) { $result = $m.FullName; break }
            }
        }
    }

    if ($result) { return $result.Trim() }
    return $null
}

$jdk = Resolve-JavaHome
if (-not $jdk) { throw "No JDK found. Set JAVA_HOME to a JDK (>=21) containing bin/$JavaBin." }
$env:JAVA_HOME = $jdk
Write-Host "[build] JAVA_HOME = $jdk" -ForegroundColor DarkGray

$mvnw = if ($IsWindowsOS) { "mvnw.cmd" } else { "mvnw" }
$mvnwPath = Join-Path $PSScriptRoot $mvnw
if (-not (Test-Path $mvnwPath)) { throw "Maven wrapper not found: $mvnwPath" }

# --- build guard (T-177, owner directive 2026-11-17): never launch too much --------------
# Three concurrent full-reactor verifies (fleet reviewers/reworks) on top of an already
# 28.1/38.9 GB committed machine exhausted commit charge and the Eclipse launcher failed
# with "Failed to create the Java Virtual Machine" (2026-11-17: launcher refusal, 0 hs_err,
# 0 WER records). One choke point here covers every fleet worker, reviewer, pre-push and
# CI invocation of this script:
#   BUILD_GUARD_MAX       max concurrent reactor builds (default 1)
#   BUILD_GUARD_MIN_MB    commit-headroom floor in MB (default 6144; 0 disables; skipped
#                         on CI runners, which are single-tenant and can't hit the incident)
#   BUILD_GUARD_WAIT_SEC  bounded wait for slot+headroom before failing fast (default 1800)
#   BUILD_GUARD_SKIP=1    bypass everything (emergency only)
# Locks are per-slot files in <git-common-dir>/build-guard/ (shared across worktrees,
# never inside target/ so `clean` cannot eat them); the OS releases the exclusive handle
# if a build dies, so a crashed build can never wedge the lane.

function Get-BuildGuardDir {
    # NOTE (T-177 test b): capture git's output WITHOUT a pipeline - `git ... |
    # Select-Object -First 1` leaves $LASTEXITCODE empty, which made this check fail
    # silently and fall back to temp while other callers used .git (two lock spaces!).
    $gitDir = $null
    try {
        $gitDir = & git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir 2>$null
        if ($LASTEXITCODE -eq 0 -and $gitDir) {
            $trimmed = ([string](@($gitDir)[0])).Trim()
            if ($trimmed) { return (Join-Path $trimmed 'build-guard') }
        }
    } catch {
        Write-Verbose "[guard] git common-dir lookup failed: $($_.Exception.Message) - falling back to temp dir"
    }
    # no git (zip checkout): per-checkout temp dir - worktrees then do not share slots
    $key = $PSScriptRoot -replace '[\\/:]', '_'
    return (Join-Path ([System.IO.Path]::GetTempPath()) "opencode-build-guard-$key")
}

function Get-BuildGuardHolder {
    param([string]$Dir)
    $out = @()
    foreach ($f in @(Get-ChildItem -Path $Dir -Filter 'build-*.info' -ErrorAction SilentlyContinue)) {
        $txt = ''
        try { $txt = (Get-Content -Path $f.FullName -Raw) } catch { $txt = '' }
        $desc = (($txt -replace "`n", ' ').Trim())
        $m = [regex]::Match($txt, 'pid=(\d+)')
        if ($m.Success) {
            if (-not (Get-Process -Id ([int]$m.Groups[1].Value) -ErrorAction SilentlyContinue)) {
                $desc += ' [pid not running - stale info]'
            }
        }
        $out += ($f.Name + ': ' + $desc)
    }
    if ($out.Count -eq 0) { return @('(no holder info found)') }
    return $out
}

function Get-CommitHeadroomMb {
    if ($IsWindowsOS) {
        try {
            $m = Get-CimInstance -ClassName Win32_PerfRawData_PerfOS_Memory -ErrorAction Stop
            return [int]((($m.CommitLimit - $m.CommittedBytes) / 1MB))
        } catch { return $null }
    }
    if (Test-Path -Path '/proc/meminfo') {
        try {
            $line = Select-String -Path '/proc/meminfo' -Pattern '^MemAvailable:\s+(\d+) kB' | Select-Object -First 1
            if ($line) { return [int]([int64]$line.Matches[0].Groups[1].Value / 1KB) }
        } catch { Write-Verbose "[guard] /proc/meminfo read failed: $($_.Exception.Message)" }
    }
    return $null # unknown platform (macOS) - caller skips the floor with a log line
}

function Enter-BuildGuard {
    param([string]$Command)
    if ($env:BUILD_GUARD_SKIP -eq '1') {
        Write-Host '[guard] BUILD_GUARD_SKIP=1 - build guard bypassed' -ForegroundColor DarkYellow
        return $null
    }
    $max = 1
    if ($env:BUILD_GUARD_MAX) { $max = [Math]::Max(1, [int]$env:BUILD_GUARD_MAX) }
    $waitSec = 1800
    if ($null -ne $env:BUILD_GUARD_WAIT_SEC -and $env:BUILD_GUARD_WAIT_SEC -ne '') { $waitSec = [Math]::Max(0, [int]$env:BUILD_GUARD_WAIT_SEC) }
    $minMb = 6144
    if ($null -ne $env:BUILD_GUARD_MIN_MB -and $env:BUILD_GUARD_MIN_MB -ne '') { $minMb = [Math]::Max(0, [int]$env:BUILD_GUARD_MIN_MB) }

    $dir = Get-BuildGuardDir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $deadline = (Get-Date).AddSeconds($waitSec)

    # 1) exclusive build slot - at most $max concurrent reactor builds
    $slot = $null
    $slotIdx = -1
    $reported = $false
    while ($null -eq $slot) {
        for ($i = 0; $i -lt $max; $i++) {
            $lockPath = Join-Path $dir "build-$i.lock"
            try {
                $slot = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
                $slotIdx = $i
                break
            } catch [System.IO.IOException] {
                # slot held by a live build - try the next one
                Write-Verbose "[guard] slot $i busy: $($_.Exception.Message)"
            }
        }
        if ($null -ne $slot) { break }
        if ((Get-Date) -ge $deadline) {
            $holders = (Get-BuildGuardHolder -Dir $dir) -join '; '
            throw "build guard: no free build slot within $waitSec s (max $max concurrent build(s)). Holders: $holders. Raise BUILD_GUARD_MAX, wait, or set BUILD_GUARD_SKIP=1 to bypass."
        }
        if (-not $reported) {
            $holders = (Get-BuildGuardHolder -Dir $dir) -join '; '
            Write-Host "[guard] waiting for a free build slot (max $max concurrent build(s)) - holders: $holders" -ForegroundColor DarkYellow
            $reported = $true
        }
        $remaining = [Math]::Max(1, [int]($deadline - (Get-Date)).TotalSeconds)
        Start-Sleep -Seconds ([Math]::Min(10, $remaining))
    }

    # holder info sidecar (the lock file itself is exclusive; waiters read THIS)
    $infoPath = Join-Path $dir "build-$slotIdx.info"
    try {
        [System.IO.File]::WriteAllText($infoPath, "pid=$PID`nstarted=$(Get-Date -Format o)`ncommand=$Command`n")
    } catch {
        Write-Verbose "[guard] holder info not written (diagnostics only): $($_.Exception.Message)"
    }

    # 2) commit-headroom floor - same deadline, released before throwing
    $guard = [pscustomobject]@{ Stream = $slot; InfoPath = $infoPath; Dir = $dir }
    if ($minMb -gt 0) {
        $freeReported = $false
        while ($true) {
            $freeMb = Get-CommitHeadroomMb
            if ($null -eq $freeMb) {
                if ($env:GITHUB_ACTIONS -eq 'true' -or $env:CI) {
                    Write-Host "[guard] CI runner detected - commit-headroom floor skipped (single-tenant, cannot reproduce the incident)" -ForegroundColor DarkGray
                } else {
                    Write-Host '[guard] commit headroom unavailable on this platform - floor skipped' -ForegroundColor DarkGray
                }
                break
            }
            if ($freeMb -ge $minMb) { break }
            if ((Get-Date) -ge $deadline) {
                Exit-BuildGuard -Guard $guard
                throw "build guard: commit headroom $freeMb MB stayed below the $minMb MB floor for $waitSec s. Wait for memory to free, set BUILD_GUARD_MIN_MB=0 to disable the floor, or BUILD_GUARD_SKIP=1 to bypass."
            }
            if (-not $freeReported) {
                Write-Host "[guard] commit headroom $freeMb MB below floor $minMb MB - waiting..." -ForegroundColor DarkYellow
                $freeReported = $true
            }
            $remaining = [Math]::Max(1, [int]($deadline - (Get-Date)).TotalSeconds)
            Start-Sleep -Seconds ([Math]::Min(10, $remaining))
        }
    }

    Write-Host "[guard] slot $slotIdx acquired in $dir (max $max concurrent build(s), floor $minMb MB)" -ForegroundColor DarkGray
    return $guard
}

function Exit-BuildGuard {
    param($Guard)
    if ($null -eq $Guard) { return }
    try { $Guard.Stream.Close() } catch { Write-Verbose "[guard] slot close failed: $($_.Exception.Message)" } # releases the exclusive handle - the slot frees here
    # drop our own holder info so a free slot never shows a stale holder (waiters that
    # raced a read tolerate the missing file; the pid-liveness check covers stragglers)
    try { Remove-Item -Path $Guard.InfoPath -Force -ErrorAction SilentlyContinue } catch { Write-Verbose "[guard] info cleanup failed: $($_.Exception.Message)" }
}

$guardState = Enter-BuildGuard -Command ("build.ps1 " + ($args -join ' '))
try {
    if ($IsWindowsOS) {
        & $mvnwPath @args
    } else {
        # Invoke the POSIX wrapper through sh: zip/Windows checkouts may lack its executable bit.
        & sh $mvnwPath @args
    }
    $buildExit = $LASTEXITCODE
} finally {
    Exit-BuildGuard -Guard $guardState
}
if ($buildExit -ne 0) { exit $buildExit }
