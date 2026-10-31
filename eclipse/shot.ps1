# shot.ps1 - capture the primary screen to PNG (Windows only).
param([string]$Out = $(Join-Path $env:TEMP "opencode\shot.png"))
$ErrorActionPreference = "Stop"

if ($IsWindows -eq $false) { throw "shot.ps1 captures the screen via System.Windows.Forms/System.Drawing and requires Windows." }

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# .NET resolves relative paths against the PROCESS working directory, not $PWD.
$Out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
try {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    } finally {
        $g.Dispose()
    }
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $bmp.Dispose()
}
Write-Output "saved $Out ($bounds)"
