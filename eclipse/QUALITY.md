# QUALITY.md — the code-quality gate

## About this document

- **Kind:** `doc` / engineering-standard reference for the `eclipse/` reactor.
- **Read by:** anyone changing harness code; invoked implicitly by every
  `.\build.ps1 verify`.
- **Related:** `AGENTS.md` (build commands), `releng/checkstyle.xml` and
  `releng/check-architecture.ps1` (the actual rules), `../ROADMAP.md`.

## What runs when

| Check | Tool | When | Fails the build? |
|---|---|---|---|
| Style / clean-code lint | Checkstyle (`releng/checkstyle.xml`) | every `verify`, every module | **yes** (error-severity rules) |
| Clean architecture | `releng/check-architecture.ps1` (exec check, repo convention) | every `validate`, once per reactor | **yes** |
| Eclipse-free purity | per-bundle `ban-eclipse-imports` exec | every build (pre-existing) | **yes** |
| Bug patterns | SpotBugs (`-Pquality`, effort Max / threshold High) | `verify -Pquality` | no — report-only for now |
| Duplication (≥100 tokens) | PMD CPD (`-Pquality`) | `verify -Pquality` | yes, within the profile |
| chat-web checks | Node `renderer-check` / `bridge-check` / `mermaid-check` | `mvn verify` (chat module) | yes |
| C++ lint/format/analysis | clang-format, cppcheck, clang-tidy + sanitizer and analysis lanes (the `cpp-tools` skill) | `cpp/ … verify` | yes |

The release gate is `.\build.ps1 clean verify -Pquality`; the inner loop is
`.\build.ps1 verify` (see `AGENTS.md` for the cadence rule).

## Clean architecture — the dependency rules

The layer cake, enforced by `check-architecture.ps1` on every build:

```
board  ->  ui  ->  core  ->  client / tools / tasks / git / fleet   (arrows point UP only)
  \-> chat -> core/client
```

- The **Eclipse-free layer** (`client`, `tools`, `tasks`, `git`, `fleet`)
  never sees SWT/JFace/workbench types (the per-bundle import ban enforces
  this) and never depends on the presentation bundles.
- `core` is the seam layer (connections, preferences, view-id contracts like
  `core.context.SessionViewIds`) - it must not depend on any presentation
  bundle either.
- `ui` must not depend on `chat`/`board`; `chat` must not depend on
  `ui`/`board`; cross-bundle reach goes through **seams in `core`** (the
  `ChatLauncher`/`SessionViewIds` pattern) or the workbench registry - never
  through mirrored string literals (the lesson of T-009).
- Nothing depends on a `*.tests` bundle.
- **No fixed machine paths** in sources - resolve at runtime (the rule that
  produced the `ECLIPSE_HOME` / Tycho-cache launcher redesign). Test *parse
  fixtures* with captured path strings are the one accepted exception.

## Clean code conventions (the enforced part)

Checkstyle enforces: no unused/redundant imports, no empty statements, no
string-literal equality, `equals`/`hashCode` pairing, no fall-through
switches, a `default` in every switch, no finalizers, braces on every block,
one statement per line, and (warn-level) upper-camel-case types plus the
standard method/field/variable naming shapes. The ruleset is deliberately
lean and high-signal: grow it only when the whole reactor is already green
under it - a lint rule that fires everywhere teaches people to ignore lint.

## Findings policy (fix at source)

Every finding the gate reports is fixed **at the source** - in the code, not
in the rules: no `@SuppressFBWarnings`, no checkstyle suppressions, no
weakened rulesets, no CPD excludes. Duplicated code is eliminated by
extraction (one shared pipeline/fixture/base class), bug patterns by
correcting the pattern itself. The 2026-09-23 campaign is the reference
pass: SpotBugs found the weak-randomness trio (MCP token + spawn password +
fleet password), a hash-absolute-value hazard, floating-point equality,
platform-default encodings, a swallowed exception and a dead store; CPD
found seven copy-pasted blocks; the tests found a starve-the-pool defect in
the then-new worker conversion. Every one fixed at the root, zero
suppressions. If a rule genuinely misfires, change the RULE with a written
rationale in this file - never silence the finding.

## Adding code

1. `.\build.ps1 verify` - green before you hand anything on.
2. New dependency edges? Check them against the layer rules above first;
   the arch check will refuse a downward edge at `validate`.
3. New third-party jars go through the target platform or the test bundle's
   `Require-Bundle`, never a bare `CLASSPATH` entry.
