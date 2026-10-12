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
| Eclipse-free purity | per-bundle pwsh import scans: `ban-eclipse-imports` (client/git/fleet; `-DskipEclipseBan=true` skips) and `check-eclipse-free` (tools/tasks, not skippable) | every build | **yes** |
| Bug patterns | SpotBugs (`-Pquality`, effort Max / threshold High) | `verify -Pquality` | no — report-only for now |
| Coverage | JaCoCo (`0.8.15`, report-only — no failing threshold) | every `verify`; HTML per module at `*/target/site/jacoco/index.html`; test-module execs carry the full-run instrumentation (production-bundle HTML aggregation: `-Pcoverage`, see the Coverage gate row below) | no — report lane |
| Coverage gate (opt-in) | JaCoCo check rule (`-Pcoverage`, 55% line at bundle scope on production bundles, aggregate report at target/site/jacoco-aggregate) | on demand: `.\build.ps1 verify -Pcoverage` | yes, when the profile is on |
| PowerShell lint | PSScriptAnalyzer (`PSScriptAnalyzerSettings.psd1`; Write-Host excluded with rationale — console scripts' interface) | every `verify` CI run; locally `.\releng\lint.ps1` | **yes** (any finding fails) |
| Markdown lint | markdownlint-cli2 (`.markdownlint-cli2.yaml`; MD013/MD060 trimmed with rationale — repo voice; task store excluded) | every `verify` CI run; locally `.\releng\lint.ps1` | **yes** (any finding fails) |
| Third-party license inventory (opt-in) | osv-scanner `--licenses` via `scan-deps.ps1 -Licenses` (the matrix-cited `license-maven-plugin` goal was removed from every maintained mycila version; osv is the free+reliable tool already pinned here) | on demand: `.\releng\scan-deps.ps1 -Licenses` | no — report-only. Landscape reviewed 2026-10-11: the "non-standard ×3" were osv metadata gaps on SNAPSHOT-parent poms, real licenses verified at Maven Central (ArchUnit = Apache-2.0, JUnit = EPL-1.0). **Policy: what WE introduce must be standard-permissive (MIT/Apache-2.0/BSD/ISC/0BSD); Eclipse-platform licenses accepted as-is.** Allowlist enforcement stays deferred until osv resolves license metadata through local parents |
| Compile-time bug patterns (opt-in) | Error Prone 2.50.0 (`-Perrorprone`; plain-javac modules only — Tycho/ECJ never runs it) | on demand: `.\build.ps1 verify -Perrorprone` | **yes, when the profile is on** |
| Mutation (opt-in) | PIT 1.30.0 (`-Pmutation`; mojo module, OSGi fragments out of scope) | on demand: `.\build.ps1 verify -Pmutation` | no — report (no threshold configured) |
| Dep vuln (SCA) | osv-scanner via `releng/scan-deps.ps1` (pinned v2.6.0 + checksum-verified; reactor poms + npm lockfile; findings fail unless listed with rationale in `releng/scan-deps-baseline.json`) | every `verify` CI run (both legs); locally `.\releng\scan-deps.ps1` | **yes** (any non-baselined finding fails) |
| Duplication (≥100 tokens) | PMD CPD (`-Pquality`) | `verify -Pquality` | yes, within the profile |
| chat-web checks | Node `renderer-check` / `bridge-check` / `mermaid-check` | `mvn verify` (chat module) | yes |
| C++ lint/format/analysis | clang-format, cppcheck, clang-tidy + sanitizer and analysis lanes (the `cpp-tools` skill) | `cpp/ … verify` | yes |

The release gate is `.\build.ps1 clean verify -Pquality`; the inner loop is
`.\build.ps1 verify` (see `AGENTS.md` for the cadence rule).

## Clean architecture — the dependency rules

The dependency graph, verified against the bundles' `META-INF/MANIFEST.MF`
`Require-Bundle` lists (internal edges; `{…}` = Eclipse platform bundles) and
enforced by `check-architecture.ps1` on every build:

```text
board  → chat · fleet · core · tasks · git · client (+ {workbench, compare})  ← no ui edge
chat   → core · client (+ {workbench})                                         ← no ui edge
ui     → core · client · tools (+ {workbench})
cdt    → core (+ {cdt.core})
core   → client · mcp (+ {runtime, resources, equinox.security})
fleet  → client · git · tasks · tools
mcp    → client · tasks · tools
tasks  → tools
git    → client
client → gson (+ the JobManager runtime {jobs, equinox.common} — the one allowlisted platform edge)
tools  → gson
```

Arrows only ever point from the presentation bundles down into the seam and
Eclipse-free layers, never the reverse.

- The **Eclipse-free layer** (`client`, `tools`, `tasks`, `git`, `fleet`)
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
