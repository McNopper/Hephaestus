# check-no-provider-ids.ps1 - the D-004 gate: no setup-specific provider/model
# identifiers in committed content (owner directive 2026-10-31). Provider/model
# choices are LOCAL settings (Eclipse preferences / user-global opencode config,
# both outside the repository); tests run on synthetic fixtures (acme/llm-*).
#
# Whitelist = the generic provider-catalog feature (public slugs -> logo assets),
# the third-party attribution (legally required vendor names), the public market
# analysis and a vendored minified library. None carry information about this setup.
#
# Runner-proofing: GitHub runner images enable PSNativeCommandUseErrorActionPreference,
# under which `git grep`'s "no matches" exit code 1 would THROW and fail this step on
# a perfectly clean tree. We pin the preference off and judge by exit code explicitly:
# 0 = matches found, 1 = no matches (clean), >= 2 = real grep error.

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$pattern = "zai-coding|xiaomi-token|kimi-code|glm-[0-9]|mimo-v2|qwen3\.[0-9]|z\.ai"

$whitelist = @(
    "eclipse/bundles/com.opencode.ide.ui/src/main/java/com/opencode/ide/ui/internal/ProviderLogos.java",
    "eclipse/bundles/com.opencode.ide.ui/fetch-logos.ps1",
    "THIRD-PARTY.md",
    "docs/competitive-landscape.md",
    "eclipse/components/chat-web/web/markdown-it.min.js",
    "releng/check-no-provider-ids.ps1"
)

$output = git grep -i -E $pattern -- .
$grepExit = $LASTEXITCODE

if ($grepExit -ge 2) {
    Write-Host "[no-provider-ids] FAIL - git grep itself failed (exit $grepExit)"
    exit 2
}

$hits = @($output | Where-Object {
    $path = ($_ -split ":")[0]
    $whitelist -notcontains $path
})

if ($hits.Count -gt 0) {
    Write-Host "[no-provider-ids] FAIL - setup-specific provider/model identifiers in committed content:"
    $hits | ForEach-Object { Write-Host "  $_" }
    Write-Host "[no-provider-ids] provider/model choices belong in local settings (D-004), never in the repo."
    exit 1
}

Write-Host "[no-provider-ids] ok - no setup-specific provider/model identifiers committed (grep exit $grepExit)"
