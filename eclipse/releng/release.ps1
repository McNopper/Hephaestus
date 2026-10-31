# release.ps1 - the D-002 release sequence for the Eclipse reactor:
# set the release version -> verify (quality gate) -> tag -> bump to the next -SNAPSHOT.
# The version change spans the whole reactor (poms, bundle MANIFESTs, feature.xml) via
# tycho-versions:set-version. The tree must be clean and on the release branch before
# anything runs; a failed gate reverts the version bump and aborts before any commit.
#
# Usage (from anywhere inside the repository):
#   eclipse\releng\release.ps1 -ReleaseVersion 0.1.10            # full release, no push
#   eclipse\releng\release.ps1 -ReleaseVersion 0.1.10 -Push      # release and push branch + tag
#   eclipse\releng\release.ps1 -ReleaseVersion 0.1.10 -DryRun    # prove the mechanics in a
#                                                                # scratch worktree, touch nothing
#   -SkipBuild re-runs the git phase without the gate (only after a green run).
#
# Policy: decision D-002. Pushing is opt-in (-Push) - the standing rule is push only on
# the owner's explicit go.
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseVersion,
    [switch]$DryRun,
    [switch]$SkipBuild,
    [switch]$Push,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"

function Step([string]$Message) { Write-Host "[release] $Message" }
function Die([string]$Message) { throw "[release] $Message" }

# --- guards -----------------------------------------------------------------

git rev-parse --show-toplevel *> $null
if ($LASTEXITCODE -ne 0) { Die "not inside a git repository" }
$repoRoot = (git rev-parse --show-toplevel).Trim()
$buildPs1 = Join-Path (Join-Path $repoRoot "eclipse") "build.ps1"
if (-not (Test-Path $buildPs1)) { Die "build wrapper not found: $buildPs1" }

if ($ReleaseVersion -notmatch '^\d+\.\d+\.\d+$') {
    Die "release version '$ReleaseVersion' is not X.Y.Z (e.g. 0.1.10)"
}
if ((git tag --list "v$ReleaseVersion")) { Die "tag v$ReleaseVersion already exists" }

$currentBranch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($currentBranch -ne $Branch) {
    Die "on branch '$currentBranch', expected '$Branch' (-Branch to override)"
}
$dirty = git status --porcelain
if ($dirty) { Die "the tree is dirty - commit or stash first:`n$dirty" }

git fetch origin --quiet
if ($LASTEXITCODE -eq 0) {
    $behind = [int](git rev-list --count "HEAD..origin/$Branch")
    if ($behind -gt 0) { Die "local $Branch is $behind commits behind origin/$Branch - pull first" }
} else {
    Step "WARNING: 'git fetch origin' failed - skipping the freshness check"
}

$parts = $ReleaseVersion.Split(".")
$nextVersion = "{0}.{1}.{2}" -f $parts[0], $parts[1], ([int]$parts[2] + 1)
# pinned to the reactor's <tycho.version> (eclipse/pom.xml) so the CLI invocation
# resolves deterministically even though pluginManagement would too
$setVersionGoal = "org.eclipse.tycho:tycho-versions-plugin:5.0.3:set-version"

function Invoke-SetVersion([string]$Root, [string]$NewVersion) {
    Push-Location (Join-Path $Root "eclipse")
    try {
        & $buildPs1 $setVersionGoal "-DnewVersion=$NewVersion" "-q"
        if ($LASTEXITCODE -ne 0) { Die "set-version $NewVersion failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
}

function Show-VersionState([string]$Root, [string]$Label) {
    $pomLine = (Select-String -Path (Join-Path $Root "eclipse\pom.xml") -Pattern "<version>(.+)</version>" | Select-Object -First 1)
    $pomVersion = if ($pomLine) { $pomLine.Matches[0].Groups[1].Value } else { "?" }
    $manifest = Get-ChildItem (Join-Path $Root "eclipse\bundles") -Recurse -Filter MANIFEST.MF |
        Where-Object { $_.FullName -notmatch '\\target\\' } |
        Sort-Object FullName |
        Select-Object -First 1
    $bundleVersion = "?"
    if ($manifest) {
        $mLine = Select-String -Path $manifest.FullName -Pattern "Bundle-Version: (.+)" | Select-Object -First 1
        if ($mLine) { $bundleVersion = $mLine.Matches[0].Groups[1].Value }
    }
    $changed = @(git -C $Root status --porcelain).Count
    Step "  [$Label] pom: $pomVersion | Bundle-Version: $bundleVersion | files changed: $changed"
}

# --- dry run: real set-version mechanics in a scratch worktree ----------------

if ($DryRun) {
    Step "DRY RUN - the main tree is not touched; set-version runs for real in a scratch worktree"
    $scratch = Join-Path ([IO.Path]::GetTempPath()) "hephaestus-release-dryrun-$PID"
    if (Test-Path $scratch) { Remove-Item -Recurse -Force $scratch }
    git worktree add --detach --quiet $scratch HEAD
    try {
        Step "scratch worktree: $scratch"
        Step "STEP 1/5 set-version -> $ReleaseVersion (executed in the worktree)"
        Invoke-SetVersion $scratch $ReleaseVersion
        Show-VersionState $scratch "release"
        Step "STEP 2/5 quality gate: eclipse\build.ps1 clean verify -Pquality (skipped in the dry run)"
        Step "STEP 3/5 git commit 'Release v$ReleaseVersion' + git tag -a v$ReleaseVersion (printed, not run)"
        Step "STEP 4/5 set-version -> $nextVersion-SNAPSHOT (executed in the worktree)"
        Invoke-SetVersion $scratch "$nextVersion-SNAPSHOT"
        Show-VersionState $scratch "bump"
        $pushNote = if ($Push) { " + git push origin $Branch --follow-tags" } else { " (no push: -Push absent)" }
        Step "STEP 5/5 git commit 'Prepare $nextVersion-SNAPSHOT'$pushNote (printed, not run)"
        Step "DRY RUN OK - the version mechanics verified, no manual edits needed"
    } finally {
        git worktree remove --force $scratch *> $null
        git worktree prune *> $null
    }
    return
}

# --- the real release ---------------------------------------------------------

Step "STEP 1/5 set-version -> $ReleaseVersion across the reactor"
Invoke-SetVersion $repoRoot $ReleaseVersion
Show-VersionState $repoRoot "release"

if ($SkipBuild) {
    Step "STEP 2/5 quality gate SKIPPED (-SkipBuild)"
} else {
    Step "STEP 2/5 quality gate: clean verify -Pquality (the release gate; minutes)"
    Push-Location (Join-Path $repoRoot "eclipse")
    try { & $buildPs1 clean verify "-Pquality" } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) {
        Step "gate FAILED - reverting the version bump, release aborted"
        git -C $repoRoot checkout -- .
        Die "quality gate failed; the tree was restored to $Branch"
    }
}

Step "STEP 3/5 commit + tag v$ReleaseVersion"
git -C $repoRoot add -A
git -C $repoRoot commit -q -m "Release v$ReleaseVersion`n`nD-002 sequence: reactor set to $ReleaseVersion (poms, MANIFESTs, feature), quality gate green."
git -C $repoRoot tag -a "v$ReleaseVersion" -m "v$ReleaseVersion"
$releaseSha = (git rev-parse --short HEAD).Trim()
Step "  tagged v$ReleaseVersion at $releaseSha"

Step "STEP 4/5 set-version -> $nextVersion-SNAPSHOT"
Invoke-SetVersion $repoRoot "$nextVersion-SNAPSHOT"
Show-VersionState $repoRoot "bump"
git -C $repoRoot add -A
git -C $repoRoot commit -q -m "Prepare $nextVersion-SNAPSHOT"
Step "  development continues at $nextVersion-SNAPSHOT"

if ($Push) {
    Step "STEP 5/5 push $Branch + tag"
    git -C $repoRoot push origin $Branch --follow-tags
    if ($LASTEXITCODE -ne 0) { Die "push failed - the release commits + tag exist locally only" }
    Step "  pushed"
} else {
    Step "STEP 5/5 push SKIPPED (-Push absent) - commits + tag are local only"
}

Step "DONE: v$ReleaseVersion tagged at $releaseSha, reactor at $nextVersion-SNAPSHOT"
