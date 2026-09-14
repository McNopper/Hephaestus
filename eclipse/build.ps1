# build.ps1 - thin wrapper around the Maven Wrapper that ensures a valid JAVA_HOME.
# The system JAVA_HOME may be missing or point to a non-existent JRE; this resolves
# a real JDK before invoking mvnw (mvnw.cmd on Windows, mvnw on unix).
# All arguments are forwarded to Maven, e.g.:  .\build.ps1 clean verify
$ErrorActionPreference = "Stop"

$IsWindowsOS = $env:OS -eq "Windows_NT" -or [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)
$JavaBin = if ($IsWindowsOS) { "java.exe" } else { "java" }

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin" $JavaBin))) { return $env:JAVA_HOME.Trim() }
    $result = $null

    # Windows: registry first, then common install dirs
    if ($IsWindowsOS) {
        foreach ($key in @("HKLM:\SOFTWARE\JavaSoft\JDK", "HKLM:\SOFTWARE\JavaSoft\Java Development Kit")) {
            if ($result) { break }
            try {
                foreach ($k in (Get-ChildItem $key -ErrorAction Stop)) {
                    $home = (Get-ItemProperty $k.PSPath -Name JavaHome -ErrorAction SilentlyContinue).JavaHome
                    if ($home -and (Test-Path "$home\bin\java.exe")) { $result = $home; break }
                }
            } catch { }
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
                if (Test-Path (Join-Path $m.FullName "bin" "java")) { $result = $m.FullName; break }
            }
        }
    }

    if ($result) { return $result.Trim() }
    return $null
}

$jdk = Resolve-JavaHome
if (-not $jdk) { throw "No JDK found. Set JAVA_HOME to a JDK (>=17) containing bin/$JavaBin." }
$env:JAVA_HOME = $jdk
Write-Host "[build] JAVA_HOME = $jdk" -ForegroundColor DarkGray

$mvnw = if ($IsWindowsOS) { "mvnw.cmd" } else { "mvnw" }
$mvnwPath = Join-Path $PSScriptRoot $mvnw
if (-not (Test-Path $mvnwPath)) { throw "Maven wrapper not found: $mvnwPath" }
if (-not $IsWindowsOS) { & chmod +x $mvnwPath }

& $mvnwPath @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
