# PSScriptAnalyzerSettings.psd1 - the PowerShell lint contract (U-086,
# code-quality-matrix: PowerShell row). Local + CI entry point: releng\lint.ps1
@{
    ExcludeRules = @(
        # Console-first scripts (build/deploy/debug/launch/lint tooling): their
        # Write-Host output IS the interface - colors, progress and sections are
        # the product for a human at a terminal. QUALITY.md findings policy:
        # changing the RULE with a written rationale, never silencing findings
        # in the scripts themselves.
        'PSAvoidUsingWriteHost'
    )
}
