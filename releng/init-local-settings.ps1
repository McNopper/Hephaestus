# init-local-settings.ps1 - generate the LOCAL provider/model settings on a fresh
# machine (never committed; D-004, owner directive 2026-10-31).
#
# Provider/model choices live ONLY in local configuration:
#   - the user-global opencode config:  ~\.config\opencode\opencode.json
#     (opencode itself reads it; the app writes the model when you connect/pick)
#   - Eclipse preferences: Window > Preferences > OpenCode (workspace-local)
# Tests never need real values - they run on synthetic fixtures.
#
# This script bootstraps the user-global file with a valid EMPTY skeleton so a
# fresh checkout has an obvious, existing place for local settings. It never
# writes concrete provider/model ids and never touches an existing file.

$ErrorActionPreference = "Stop"

$dir = Join-Path $env:USERPROFILE ".config\opencode"
$file = Join-Path $dir "opencode.json"

if (Test-Path $file) {
    Write-Host "[init] $file already exists - left untouched."
    Write-Host "[init] your provider/model choices stay in this local file; they are never committed."
    exit 0
}

New-Item -ItemType Directory -Force -Path $dir | Out-Null
$skeleton = @"
{
  "`$schema": "https://opencode.ai/config.json"
}
"@
[IO.File]::WriteAllText($file, $skeleton, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[init] created $file (valid empty skeleton)"
Write-Host "[init] connect a provider (opencode auth login, or /connect in the app) and pick a model;"
Write-Host "[init] the choice lands in this LOCAL file and is never committed."
Write-Host "[init] Eclipse side: Window > Preferences > OpenCode stays blank to follow the server default."
