# QUALITY.md — the code-quality gate

## About this document

- **Kind:** `doc` / engineering-standard reference for the `eclipse/` reactor.
- **Read by:** anyone changing harness code; invoked implicitly by every
  `.\build.ps1 verify`.
- **Related:** `../AGENTS.md` (build commands), `releng/checkstyle.xml` and
  `releng/check-architecture.ps1` (the actual rules), `../docs/HISTORY.md`
  (the record of the cleanup passes), `../ROADMAP.md`.

## What runs when

| Check | Tool | When | Fails the build? |
|---|---|---|---|
| Style / clean-code lint | Checkstyle (`releng/checkstyle.xml`) | every `verify`, every module | **yes** (error-severity rules) |
| Format | Spotless 3.10.4 + google-java-format 1.28.0 (`spotless:check`; GJF pinned — 1.37.0 changed the `JavaFormatterOptions.Style` API both spotless lines link against; `eclipse/.mvn/jvm.config` carries the JDK `--add-exports` GJF needs in-process) | every `verify`, every module | **yes** |
| Architecture rules (package level) | ArchUnit over the reactor bytecode (`bundles/com.opencode.ide.arch.tests`; namespace, layer graph, Eclipse-free purity, bundle cycles) | every `verify` | **yes** |
| Clean architecture + encoding | `releng/check-architecture.ps1` (exec check, repo convention: layers, no fixed paths, no mojibake) | every `validate`, once per reactor | **yes** |
| Eclipse-free purity | per-bundle pwsh import scans: `ban-eclipse-imports` (client/git; `-DskipEclipseBan=true` skips) and `check-eclipse-free` (tools/tasks, not skippable) | every build | **yes** |
| Bug patterns | SpotBugs (`-Pquality`, effort Max / threshold High) | `verify -Pquality` | no — report-only for now |
| Coverage | JaCoCo (`0.8.15`, report-only — no failing threshold) | every `verify`; HTML per module at `*/target/site/jacoco/index.html`; test-module execs carry the full-run instrumentation (production-bundle HTML aggregation: `-Pcoverage`, see the Coverage gate row below) | no — report lane |
| Coverage gate (opt-in) | JaCoCo check rule (`-Pcoverage`, 55% line at bundle scope on production bundles, aggregate report at target/site/jacoco-aggregate) | on demand: `.\build.ps1 verify -Pcoverage` | yes, when the profile is on |
| Coverage manifest (test ids + skip reasons) | `releng/coverage-manifest.ps1` diff of this build's surefire XML reports against `releng/coverage-manifest-baseline.txt` (both lanes) | after every successful `verify`/`install`/`deploy` through `build.ps1` (CI + pre-push ride that path; `-Update -Since <build-start>` re-baselines) | **yes** for the coverage contract (missing/extra ids and both-skip reason mismatches); **no** for pass↔skip state flips — tolerated with a visible `[manifest] ENV-DRIFT (state flip tolerated …)` warning (T-209) |
| PowerShell lint | PSScriptAnalyzer (`PSScriptAnalyzerSettings.psd1`; Write-Host excluded with rationale — console scripts' interface) | every `verify` CI run; locally `.\releng\lint.ps1` | **yes** (any finding fails) |
| Markdown lint | markdownlint-cli2 (`.markdownlint-cli2.yaml`; MD013/MD060 trimmed with rationale — repo voice; task store excluded) | every `verify` CI run; locally `.\releng\lint.ps1` | **yes** (any finding fails) |
| Third-party license inventory (opt-in) | osv-scanner `--licenses` via `scan-deps.ps1 -Licenses` (the matrix-cited `license-maven-plugin` goal was removed from every maintained mycila version; osv is the free+reliable tool already pinned here) | on demand: `.\releng\scan-deps.ps1 -Licenses` | no — report-only. Landscape reviewed 2026-10-11: the "non-standard ×3" were osv metadata gaps on SNAPSHOT-parent poms, real licenses verified at Maven Central (ArchUnit = Apache-2.0, JUnit = EPL-1.0). **Policy: what WE introduce must be standard-permissive (MIT/Apache-2.0/BSD/ISC/0BSD); Eclipse-platform licenses accepted as-is.** Allowlist enforcement stays deferred until osv resolves license metadata through local parents |
| Compile-time bug patterns (opt-in) | Error Prone 2.50.0 (`-Perrorprone`; plain-javac modules only — Tycho/ECJ never runs it) | on demand: `.\build.ps1 verify -Perrorprone` | **yes, when the profile is on** |
| Mutation (opt-in) | PIT 1.30.0 (`-Pmutation`; mojo module, OSGi fragments out of scope) | on demand: `.\build.ps1 verify -Pmutation` | no — report (no threshold configured) |
| Dep vuln (SCA) | osv-scanner via `releng/scan-deps.ps1` (pinned v2.6.0 + checksum-verified; reactor poms + npm lockfile; findings fail unless listed with rationale in `releng/scan-deps-baseline.json`) | every `verify` CI run (both legs); locally `.\releng\scan-deps.ps1` | **yes** (any non-baselined finding fails) |
| Duplication (≥100 tokens) | PMD CPD (`-Pquality`) | `verify -Pquality` | yes, within the profile |
| chat-web checks | Node `renderer-check` / `bridge-check` / `mermaid-check` | `mvn verify` (chat module) | yes |
| C++ lint/format/analysis | clang-format, cppcheck, clang-tidy + sanitizer and analysis lanes | `cpp/ … verify` | yes |
| Pre-push gate (the CI mirror) | `releng/pre-push.ps1` via `.githooks/pre-push` + `core.hooksPath` (a local setting set by `releng/init-local-settings.ps1`) | every `git push` from this checkout - CI's four checks (lint, D-004, `build.ps1 clean verify`, scan-deps) must pass locally first; fail-fast, non-zero blocks the push | **yes** (blocks the push, owner directive 2026-11-16 / T-129) |
| Build guard (launch limiter) | built into `build.ps1` itself (T-177) - exclusive slot locks in `<git-common-dir>/build-guard/` + a commit-headroom floor | every `build.ps1` invocation: at most `BUILD_GUARD_MAX` reactor builds (default 1), each with >= `BUILD_GUARD_MIN_MB` free commit (default 6144; `0` disables; skipped on CI runners), bounded by `BUILD_GUARD_WAIT_SEC` (default 1800) before it fails fast naming every holder; `BUILD_GUARD_SKIP=1` bypasses | **yes** (owner directive 2026-11-17: "we need a guard, that we are launching not too much" - 3 concurrent fleet verifies exhausted commit charge and the Eclipse launcher failed with "Failed to create the Java Virtual Machine": idle already 28.1/38.9 GB committed, 0 hs_err, 0 WER records) |

The release gate is `.\build.ps1 clean verify -Pquality`; the inner loop is
`.\build.ps1 verify` (see `AGENTS.md` for the cadence rule). Nothing lands on
`main` that CI has not already passed locally: the pre-push gate runs the CI
verify workflow's checks before every push, so a green push is a green CI run
(barring runner drift).

## The hybrid test lane — plain surefire vs tycho-surefire (T-008)

Test bundles split by what they need at runtime:

| Lane | Bundles | Executor |
|---|---|---|
| Plain (Eclipse-free) | `tools.tests`, `tasks.tests`, `client.tests`, `git.tests` | `maven-surefire-plugin` 3.2.5, plain JVM, no OSGi boot |
| OSGi (needs DS/platform) | `ui.tests`, `cdt.tests`, `board.tests`, `core.tests`, `mcp.tests`, `chat.tests` | `tycho-surefire-plugin` (Equinox test runtime), unchanged |

**chat.tests audit result (T-008):** chat was a *candidate* for the plain
lane ("audited chat"), but its tests are plain-JVM-safe only **except** the
U-105 governed `/goal` seam
(`ChatSessionControllerTest#theGoalCommandIsInterceptedAndNeverReachesTheServer`):
`ChatViewSettings` persists the remembered goal ticket through
`ChatActivator.getDefault()`, which is `null` outside an OSGi runtime
("persistence is a no-op (tests)"), so in a plain JVM `/goal pause` never
finds the captured ticket (status stays `ready`) while the OSGi run passes.
One test pins the whole bundle to the lane where its production seam really
exists - chat stays on the OSGi lane, full coverage intact. The failure was
observed live in both the single-bundle probe and the full-reactor plain run
(2026-11-23, expected `paused`, got `ready`).

Both lanes keep packaging `eclipse-test-plugin` (target-platform compile
fidelity — only the TEST EXECUTOR swaps), and the plain-javac modules
(`mojo`, `arch.tests`) keep the default surefire binding. The plain-lane poms
carry these guards, each with a written rationale in the pom itself:

- **`testClassesDirectory` → `target/classes`** — these tests live in
  `src/main/java`; without the override surefire scans the empty
  `target/test-classes` and silently discovers ZERO tests. `failIfNoTests=true`
  (parent pluginManagement, enforced everywhere surefire runs) turns that
  failure mode into a build failure instead of a green no-op.
- **Forced `surefire-junit4` plugin dependency** — the JUnit jar reaches the
  classpath as a Tycho p2 artifact (`p2.eclipse.plugin:org.junit`) whose
  coordinates miss surefire's provider auto-detection (it fell back to the
  JUnit3 provider: 0 tests executed). Plugin dependencies never enter the
  target platform.
- **`argLine` → `${tycho.testArgLine}`** — jacoco's `prepare-agent` sees
  tycho-surefire declared in the module and sets `tycho.testArgLine`; the
  plain fork must be handed that string or coverage silently becomes exec-less.
- **Fork env vars mirrored** (`CLIENT_REPLY_POLL_MS`/`CLIENT_TURN_QUIET_MS`,
  `GIT_CONFIG_NOSYSTEM=1`) — the plain fork runs with the same environment as
  the OSGi fork.

**Weekly OSGi-parity run (MANIFEST fidelity).** `.\build.ps1 verify
-Posgi-parity` flips the four plain-lane bundles back to tycho-surefire (and plain
surefire off) without touching the OSGi-native bundles. Run it weekly in the
maintenance pass and before any release: both lanes write the same
`target/surefire-reports`, the coverage manifest is lane-agnostic, and the
manifest must stay CLEAN (byte-identical test ids + skip reasons against the
baseline; a pass↔skip state flip between the lanes is only a visible tolerated
ENV-DRIFT warning — see the semantics below — while id or both-skip reason
drift stays a DRIFT). A DRIFT means the plain lane and the OSGi runtime no longer agree.
This is also the MANIFEST fidelity proof — the OSGi boot honors
`Require-Bundle`/`Import-Package` only if the manifests still describe what
the tests actually load, while the plain lane runs on Tycho's manifest-resolved
dependency closure as its classpath. (There is no `tycho-dependency-analyzer`
artifact in Tycho 5.0.x — this documented weekly OSGi run is the fidelity
check the T-008 acceptance criteria allow.)

**Coverage manifest mechanics.** `releng/coverage-manifest.ps1` collects
`TEST-*.xml` from every test-bearing module of THIS build — `-Since <build
start>` (stamped by `build.ps1` before mvnw) excludes stale leftovers, so a
test deleted since the last `clean` shows up as a MISSING id instead of being
masked by an old report. Gate modes: diff (default) and
`-Update` (re-baseline; do it consciously in the same change that adds,
removes or re-skips tests, and cite the rationale on the ticket). Partial
reactor runs (`-pl`/`-am`/`-rf`/`-N`) and test-skipped commands
(`-DskipTests`, `-Dmaven.test.skip`) skip the diff with a loud console note —
never silently.

**Gate regression suite (opt-in, T-213).** The gate's six-case verdict
contract (missing baseline, removed id, tampered both-skip reason, pristine
CLEAN, and both pass↔skip flip directions) re-runs on demand:
`cd eclipse; .\releng\coverage-manifest-tests.ps1` (or
`pwsh -NoProfile -File eclipse\releng\coverage-manifest-tests.ps1`) — it
requires surefire reports from a recent build (`.\build.ps1 verify` first;
fails with a "run a build first" message otherwise), swaps only temp copies
with a try/finally byte-restore plus a before/after SHA256 proof, prints one
row per case + overall PASS/FAIL, and exits 0 only when all six expectations
hold — deliberately NOT wired into `build.ps1` or CI, so the local gate stays
exactly as fast.

**Diff semantics (T-209).** Three different kinds of difference, three
different verdicts:

- **Test ids — the hard coverage contract.** The id set (`bundle|class#method`)
  is environment-independent (every test compiles from `src/main/java`), so a
  **MISSING** id (in the baseline, absent from this build) or an **EXTRA** id
  (in this build, absent from the baseline) fails the gate, always.
- **State flips — tolerated, visibly.** A `pass`→`skip` or `skip`→`pass`
  flip is an environment fact (a machine-dependent test ran on one runner and
  skipped on another), not drift. The gate prints a visible
  `[manifest] ENV-DRIFT (state flip tolerated - environment-dependent test):`
  block with the same `CHANGED` detail lines and **exits 0** for flips alone.
- **Both-skip reasons — strict.** When a test is skipped on both sides, the
  reason strings are compared strictly: different wording is a CHANGED line
  and **fails** (the T-208 tamper case), because a changed reason can mean the
  test changed what it waits for, not just where it ran. A missing baseline
  also fails (fail-closed bootstrap).

*Skip-reason provenance (the AC2 documentation gap):* the reason strings the
gate compares are the **source tests' own `assumeTrue(…)` literals / framework
skip messages** captured from the surefire XML — they are authored in the test
code, not invented by the gate (the 5-row baseline↔source mapping lives on
ticket T-208).

*Known accepted edge (cross-env wording):* two machines can skip the *same*
test with *differently-worded* environment reasons (hypothetical example:
`tool not on this machine` vs `tool install is broken on this machine` — the
pre-T-209 cppcheck wording pair once quoted here was canonicalized into a
single per-method skip constant, so it no longer exists in source). Because
both-skip reasons stay strict by design, that case would still hard-fail the
gate on the second machine — accepted for now (reason-strictness is
deliberately not weakened further); the mitigation if it ever bites is to
**re-baseline from CI** (`-Update -Since <build-start>` on a green runner)
and record the rationale on the ticket.

## Clean architecture — the dependency rules

The dependency graph, verified against the bundles' `META-INF/MANIFEST.MF`
`Require-Bundle` lists (internal edges; `{…}` = Eclipse platform bundles) and
enforced by `check-architecture.ps1` on every build:

```text
board  → chat · core · tasks · git · client (+ {workbench, compare})  ← no ui edge
chat   → core · tasks · client (+ {workbench})                            ← no ui edge
ui     → core · client · tools (+ {workbench})
cdt    → core (+ {cdt.core})
core   → client · mcp (+ {runtime, resources, equinox.security})

mcp    → client · tasks · tools
tasks  → tools
git    → client
client → gson (+ the JobManager runtime {jobs, equinox.common} — the one allowlisted platform edge)
tools  → gson
```

Arrows only ever point from the presentation bundles down into the seam and
Eclipse-free layers, never the reverse.

- The **Eclipse-free layer** (`client`, `tools`, `tasks`, `git`)
  never sees SWT/JFace/workbench types (the per-bundle import scans enforce
  this; client's single allowlist is the plain-JVM-safe JobManager runtime)
  and never depends on the presentation bundles.
- `core` is the seam layer (connections, preferences, view-id contracts like
  `core.context.SessionViewIds`) - it must not depend on any presentation
  bundle either.
- `ui` must not depend on `chat`/`board`; `chat` must not depend on
  `ui`/`board`; cross-bundle reach goes through **seams in `core`** (the
  `ChatLauncher`/`SessionViewIds` pattern) or the workbench registry - never
  through mirrored string literals. (B-017 in `../ROADMAP.md`: ui's
  view/command ids are not yet routed through those seams - the rule is the
  target, not yet the current state.)
- A `*.tests` fragment never requires another tests bundle - the one edge
  class the arch scan checks for in `check-architecture.ps1`.
- **No fixed machine paths** in sources - resolve at runtime (the rule that
  produced the `ECLIPSE_HOME` / Tycho-cache launcher redesign). Test *parse
  fixtures* with captured path strings are the one accepted exception.
- **No mojibake** in the harness sources and the repo docs (skills, agents,
  `docs/`, top-level Markdown): double-encoded UTF-8 - text saved through an
  ANSI code-page round-trip, which turns every non-ASCII character into two or
  three cp1252 characters - fails the check. Vendored third-party bundles
  (`hljs/`, `katex/`, `*.min.*`) are out of scope; intentional fixtures spell
  such characters as `\u` escapes. The task store is runtime data and is not
  scanned (`task_doctor` lints it).

## Clean code conventions (the enforced part)

Checkstyle enforces (error severity unless noted): no unused/redundant
imports, no empty statements, no string-literal equality, `equals`/`hashCode`
pairing, no covariant `equals` (`CovariantEquals`), no fall-through switches,
a `default` in every switch, no finalizers (`NoFinalizer`), no redundant
boolean comparisons (`SimplifyBooleanExpression`), braces on every block
(`NeedBraces`), one statement per line (`OneStatementPerLine`), one outer
type per file (`OuterTypeFilename`), and (warn-level) upper-camel-case types
plus the standard method/field/variable naming shapes (`TypeName`,
`MethodName`, `MemberName`, `LocalVariableName`). The ruleset is deliberately
lean and high-signal: grow it only when the whole reactor is already green
under it - a lint rule that fires everywhere teaches people to ignore lint.

## Findings policy (fix at source)

Every finding the gate reports is fixed **at the source** - in the code, not
in the rules: no `@SuppressFBWarnings`, no checkstyle suppressions, no
weakened rulesets, no CPD excludes. Duplicated code is eliminated by
extraction (one shared pipeline/fixture/base class), bug patterns by
correcting the pattern itself. If a rule genuinely misfires, change the RULE
with a written rationale in this file - never silence the finding. (The
record of the cleanup passes themselves - what SpotBugs/CPD found and how
each finding was fixed at the root - lives in `../docs/HISTORY.md`, not
here.)

## Adding code

1. `.\build.ps1 verify` - green before you hand anything on.
2. New dependency edges? Check them against the layer rules above first;
   the arch check will refuse a downward edge at `validate`.
3. New third-party jars go through the target platform or the test bundle's
   `Require-Bundle`, never a bare `CLASSPATH` entry.
