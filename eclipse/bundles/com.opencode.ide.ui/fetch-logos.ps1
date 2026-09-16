# fetch-logos.ps1 — refresh the vendored provider logos for the Providers view.
#
# Retrieves provider logo SVGs from Artificial Analysis
# (https://artificialanalysis.ai/img/logos/<slug>_small.svg), vendors them under
# src/main/resources/icons/providers/svg/, and rasterizes each to 16px and 32px
# transparent PNGs next to them (consumed by com.opencode.ide.ui.internal.ProviderLogos).
#
# Provenance, trademark ownership and the retrieval date are recorded in the
# repo root THIRD-PARTY.md, section "Provider logos" — update it when refreshing.
#
# Requirements: pwsh 7+, curl.exe, and Python with cairosvg
# (`pip install cairosvg`) for SVG -> PNG rasterization.
#
# Usage (from this bundle directory):  .\fetch-logos.ps1
#   A run keeps only HTTP 200 responses whose body starts with '<' (SVG) and
#   skips any SVG larger than 200 KB. Slugs that 404 are reported at the end.

[CmdletBinding()]
param(
    # Candidate slugs, tried in order; alternates for the same provider sit next to each other.
    [string[]]$Slugs = @(
        'anthropic', 'openai', 'google',
        'xai', 'x-ai', 'grok',                 # all 404 as of 2026-08-18
        'deepseek', 'meta',
        'mistral', 'mistral-ai', 'mistralai',  # 404 as of 2026-08-18
        'microsoft', 'nvidia',
        'moonshot', 'moonshotai', 'moonshot-ai', 'kimi',  # 404 as of 2026-09-16 - the shipped kimi logo is original artwork (see THIRD-PARTY.md)
        'qwen', 'alibaba',
        'amazon', 'amazon-web-services', 'amazon-nova', 'aws',
        'cohere',
        'perplexity', 'perplexity-ai',         # 404 as of 2026-08-18
        'zai', 'z-ai',
        'bytedance', 'baidu', 'minimax',
        'openrouter', 'github'
    ),
    [string]$BaseUrl = 'https://artificialanalysis.ai/img/logos',
    # Keep this in sync with ProviderLogos.java (slugs actually shipped).
    # 'kimi' is intentionally NOT here: Artificial Analysis has no Moonshot/Kimi
    # logo (404 as of 2026-09-16), so the shipped kimi PNGs are original
    # artwork, not fetched — refreshing must not overwrite them.
    [string[]]$Ship = @(
        'alibaba', 'anthropic', 'aws', 'baidu', 'bytedance', 'cohere', 'deepseek',
        'github', 'google', 'meta', 'microsoft', 'minimax', 'nvidia', 'openai',
        'openrouter', 'zai'
    )
)

$ErrorActionPreference = 'Stop'
$providersDir = Join-Path $PSScriptRoot 'src/main/resources/icons/providers'
$svgDir = Join-Path $providersDir 'svg'
New-Item -ItemType Directory -Force -Path $svgDir | Out-Null
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "opencode-fetch-logos-$([guid]::NewGuid().ToString('N').Substring(0,8))"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

$ok = [System.Collections.Generic.List[string]]::new()
$failed = [System.Collections.Generic.List[string]]::new()
try {
    foreach ($slug in $Slugs) {
        $target = Join-Path $tempDir "$slug.svg"
        $code = & curl.exe -sS -L --max-time 30 -o $target -w '%{http_code}' "$BaseUrl/${slug}_small.svg" 2>$null
        $bytes = if (Test-Path $target) { [System.IO.File]::ReadAllBytes($target) } else { , @() }
        if ($code -eq '200' -and $bytes.Length -gt 0 -and [char]$bytes[0] -eq '<' -and $bytes.Length -le 200KB) {
            Copy-Item $target (Join-Path $svgDir "$slug.svg") -Force
            $ok.Add($slug)
            Write-Host "OK      $slug ($($bytes.Length) bytes)"
        } else {
            $failed.Add("$slug (HTTP $code)")
            Write-Host "SKIP    $slug (HTTP $code)"
        }
    }

    foreach ($slug in $ok) {
        if ($slug -notin $Ship) {
            Write-Host "NOTE    $slug downloaded but not in -Ship list; rasterized anyway — extend ProviderLogos.java if you want it"
        }
        $svg = Join-Path $svgDir "$slug.svg"
        foreach ($size in 16, 32) {
            $png = Join-Path $providersDir "${slug}_$size.png"
            python -m cairosvg $svg -o $png --output-width $size --output-height $size
            if ($LASTEXITCODE -ne 0) {
                throw "cairosvg failed for $slug at ${size}px (exit $LASTEXITCODE). Install it with: python -m pip install cairosvg"
            }
            Write-Host "PNG     $(Split-Path $png -Leaf)"
        }
    }
} finally {
    Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Succeeded: $($ok.Count) — $($ok -join ', ')"
Write-Host "Failed   : $($failed.Count) — $($failed -join ', ')"
if ($failed.Count -gt 0) {
    Write-Host 'Failed slugs keep the letter-badge fallback in the Providers view; probe alternate spellings and add them to -Slugs.'
}
