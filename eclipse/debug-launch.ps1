# debug-launch.ps1 - launches eclipse-cpp in the background with the OSGi console,
# queries the opencode bundles' state (ss/diag), captures the platform log, then
# shuts it down. Uses a throwaway workspace so the user's real workspace is untouched.
param(
    [int]$Port = 14923,
    [int]$UpSeconds = 35,
    [int]$SettleSeconds = 35,
    [string]$EclipseRoot = $(if ($env:ECLIPSE_HOME) { $env:ECLIPSE_HOME } else { "C:\eclipse-cpp" })
)
$ErrorActionPreference = "Continue"
$eclipse = Join-Path $EclipseRoot "eclipsec.exe"
$ocTemp  = Join-Path $env:TEMP "opencode"
$ws      = Join-Path $ocTemp "eclipse-ws"
$log     = Join-Path $ocTemp "eclipse-console.log"

if (-not (Test-Path -LiteralPath $eclipse)) { throw "eclipsec.exe not found at $eclipse (set -EclipseRoot or ECLIPSE_HOME)" }
New-Item -ItemType Directory -Path $ocTemp -Force | Out-Null
Remove-Item -LiteralPath $log -ErrorAction SilentlyContinue
Remove-Item -LiteralPath "$log.err" -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $ws)) { New-Item -ItemType Directory -Path $ws -Force | Out-Null }

# warn if an eclipse is already running (would lock the shared configuration)
$existing = Get-Process -Name eclipse,eclipsec -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "[debug] WARNING: eclipse already running (pid(s): $($existing.Id -join ',')). Close it for a clean check." -ForegroundColor Red
}

Write-Host "[debug] launching eclipse-cpp (console port $Port, workspace $ws)..." -ForegroundColor Cyan
# -ArgumentList joins with a bare space WITHOUT quoting, so the workspace path
# (which sits under $env:TEMP and may contain spaces) must be quoted here.
$proc = Start-Process -FilePath $eclipse `
    -ArgumentList "-console","$Port","-consoleLog","-data","`"$ws`"" `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
Write-Host "[debug] eclipse pid=$($proc.Id)"

function Read-Stream($stream, [int]$seconds) {
    # line-based read (ASCII) so multi-line console output is captured reliably
    try { $stream.ReadTimeout = 800 } catch { Write-Verbose "stream has no settable ReadTimeout" }
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
    $sb = New-Object System.Text.StringBuilder
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $line = $reader.ReadLine()
            if ($null -ne $line) { [void]$sb.AppendLine($line) }
        } catch { Start-Sleep -Milliseconds 150 }
    }
    return $sb.ToString()
}

function Invoke-Osgi($client, $cmd, [int]$wait = 3) {
    $stream = $client.GetStream()
    $w = New-Object System.IO.StreamWriter($stream)
    $w.AutoFlush = $true
    $w.WriteLine($cmd)
    return Read-Stream $stream $wait
}

$connected = $false
$elapsed = 0
for ($i = 0; $i -lt 150; $i++) {
    Start-Sleep -Seconds 1
    $elapsed = $i + 1
    try {
        $test = New-Object System.Net.Sockets.TcpClient
        $test.Connect("127.0.0.1", $Port)
        $test.Close()
        $connected = $true; break
    } catch { Write-Verbose "console probe attempt failed" }
}
Write-Host "[debug] console port up=$connected after ${elapsed}s" -ForegroundColor Cyan

if ($connected) {
    # let the workbench finish starting (and the opencode bundles start) before querying
    Write-Host "[debug] waiting ${SettleSeconds}s for workbench to settle..." -ForegroundColor Cyan
    Start-Sleep -Seconds $SettleSeconds

    $client = New-Object System.Net.Sockets.TcpClient("127.0.0.1", $Port)
    [void](Read-Stream $client.GetStream() 3)  # drain banner/prompt

    Write-Host "`n========== control: ss org.eclipse.ui (proves console works) ==========" -ForegroundColor Yellow
    Write-Host -Object (Invoke-Osgi -client $client -cmd "ss org.eclipse.ui" -wait 6)

    Write-Host "========== ss opencode ==========" -ForegroundColor Yellow
    Write-Host -Object (Invoke-Osgi -client $client -cmd "ss opencode" -wait 6)

    Write-Host "========== diag com.opencode.ide.ui ==========" -ForegroundColor Yellow
    Write-Host -Object (Invoke-Osgi -client $client -cmd "diag com.opencode.ide.ui" -wait 6)
    $client.Close()
}

Write-Host "[debug] letting workbench run ${UpSeconds}s to capture startup logs..." -ForegroundColor Cyan
Start-Sleep -Seconds $UpSeconds

# eclipsec.exe may spawn the JVM as a child; System.Diagnostics.Process has no
# Descendants(), so walk the process tree ourselves - otherwise a child keeps
# running and holds the throwaway workspace lock. Collect BEFORE killing the
# parent: once it dies its children are re-parented and no longer match.
function Get-DescendantId([int]$parentId) {
    $ids = @()
    foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parentId" -ErrorAction SilentlyContinue)) {
        $ids += [int]$child.ProcessId
        $ids += Get-DescendantId ([int]$child.ProcessId)
    }
    return $ids
}
$descendants = @()
try { $descendants = @(Get-DescendantId $proc.Id) } catch { Write-Host "[debug] child scan failed: $_" }

try { Stop-Process -Id $proc.Id -Force -ErrorAction Stop; Write-Host "[debug] stopped eclipse pid=$($proc.Id)" }
catch { Write-Host "[debug] stop failed: $_" }
foreach ($id in $descendants) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }

Write-Host "`n========== platform log: opencode/error/perspective lines ==========" -ForegroundColor Yellow
if (Test-Path $log) {
    Get-Content $log -ErrorAction SilentlyContinue |
        Select-String -Pattern "opencode|ERROR|could not|Unresolved|missing requirement|requires '|perspective|org.eclipse.ui" -CaseSensitive:$false |
        Select-Object -First 80 | ForEach-Object { Write-Host $_.Line }
}
$errLog = "$log.err"
if ((Test-Path $errLog) -and (Get-Item $errLog).Length -gt 0) {
    Write-Host "`n========== stderr (.err) ==========" -ForegroundColor Yellow
    Get-Content $errLog | Select-Object -First 30 | ForEach-Object { Write-Host $_ }
}
