---
name: Code Quality Matrix
description: Free, reliable code-quality tools by programming language and use case — linter, formatter, static analysis/SAST, dependency vulnerability (SCA), license check, architecture rules, tests, coverage, mutation, fuzzing, docs. Use when choosing or recommending quality tooling for a language, auditing a repo for missing quality tools, adding missing linting/analysis/dependency-scanning tooling to a project, or asking what tools exist for lint, format, static analysis, dependency scanning, coverage, or architecture enforcement.
---

# Code Quality Matrix

Selection bar for every entry: **free** (open source or genuinely free) **and**
**reliable** (de-facto standard of its ecosystem, or strong adoption plus active
maintenance). No commercial/subscription tools. No niche or unmaintained tools.
Where one tool is the ecosystem standard, only that one is named.

Last researched: 2026-10. Sources: awesome-static-analysis, OWASP source-code
analysis and SCA catalogs, 2026 SAST/SCA tool comparisons, official project docs.

This matrix is **generic and authoritative**: it drives repo tooling choices,
never the other way around. For any repo, map its languages to the matrix rows
and let the matrix pick the tools. Repo-local conventions may refine how a tool
is wired (build target names, config location), but must not contradict the
matrix or silently skip a covered use case.

## At a glance

Cell legend: tools separated by **commas or `+` are complementary layers — use
them together** (e.g. clang-tidy + cppcheck). Tools separated by **or** and
entries marked `(alt: X)` are **alternatives — pick exactly one**.

| Language | Lint / style | Format | Static / SAST | Dep vuln (SCA) | License | Arch rules | Tests | Coverage | Mutation | Fuzzing | Docs |
|---|---|---|---|---|---|---|---|---|---|---|---|
| C | clang-tidy, cppcheck | clang-format | clang-tidy, scan-build | osv-scanner (or Trivy) | ScanCode Toolkit | custom include-graph check | GoogleTest or Catch2 | llvm-cov (Clang lane), gcov+lcov (GCC lane) | — (no standard) | libFuzzer or AFL++ | Doxygen |
| C++ | clang-tidy, cppcheck | clang-format | clang-tidy, scan-build | osv-scanner (or Trivy) | ScanCode Toolkit | custom include-graph check | GoogleTest or Catch2 | llvm-cov (Clang lane), gcov+lcov (GCC lane) | — (no standard) | libFuzzer or AFL++ | Doxygen |
| Java | Checkstyle, SpotBugs, PMD | Spotless (google-java-format) | Error Prone, SpotBugs | OWASP Dependency-Check or osv-scanner | license-maven-plugin | ArchUnit | JUnit 5 | JaCoCo | PIT | Jazzer | Javadoc (built-in) |
| Python | Ruff | Ruff format (alt: Black) | mypy, Bandit, Semgrep OSS | pip-audit (+ osv-scanner for mixed repos) | pip-licenses | import-linter | pytest | coverage.py / pytest-cov | mutmut | Atheris | Sphinx or MkDocs |
| TypeScript / JavaScript | ESLint (+ typescript-eslint) | Prettier | tsc --noEmit, Semgrep OSS | npm/pnpm audit (+ osv-scanner) | license-checker | dependency-cruiser | Vitest (e2e: Playwright) | Vitest v8 / Istanbul | Stryker | — (Jazzer.js exists but is early-stage) | TypeDoc |
| HTML / CSS | htmlhint, Stylelint | Prettier | Semgrep OSS | retire.js (CDN/legacy JS) | — | — | Playwright + axe-core | — | — | — | — |
| Markdown | markdownlint-cli2, Vale (prose style), mermaid-cli (diagrams) | Prettier | — | — | — | — | lychee (link check) | — | — | — | — |
| Config / data (YAML, TOML, JSON, XML, SQL, HCL) | yamllint (YAML), actionlint (CI), taplo (TOML), sqlfluff (SQL), tflint (HCL) | Prettier (YAML/JSON), taplo fmt (TOML), terraform fmt (HCL) | check-jsonschema | — | — | — | — | — | — | — | — |
| LaTeX | ChkTeX | latexindent | — | — | — | — | — | — | — | — | — |
| CMake | cmake-lint | cmake-format | — | — | — | — | — | — | — | — | — |
| PowerShell | PSScriptAnalyzer | PSScriptAnalyzer (Invoke-Formatter) | PSScriptAnalyzer | — | — | — | Pester | — | — | — | — |
| Shell | shellcheck | shfmt | shellcheck | — | — | — | bats-core | — | — | — | — |
| Go | golangci-lint (bundles staticcheck, go vet) | gofmt | staticcheck, go vet (via golangci-lint) | govulncheck | — | depguard (in golangci-lint) | go test | go test -cover | — | native fuzzing | go doc |
| Rust | Clippy | rustfmt | Clippy (correctness lints) | cargo-audit + cargo-deny | cargo-deny | cargo-deny bans | cargo test | llvm-cov or tarpaulin | cargo-mutants | cargo-fuzz | rustdoc |
| C# | .NET analyzers, StyleCop | dotnet format | .NET analyzers, SecurityCodeScan | dotnet list package --vulnerable | — | NetArchTest | xUnit or NUnit | coverlet | Stryker.NET | — | DocFX |
| Kotlin | ktlint, detekt | ktlint | detekt | OWASP Dependency-Check or osv-scanner | — | — | JUnit 5 or Kotest | JaCoCo or Kover | PIT | Jazzer | Dokka |

"—" means there is no free, reliable standard tool for that cell. Skip the use
case rather than adopting a weak tool.

## Per-language detail

Languages listed only in the at-a-glance matrix (Go, Rust, C#, Kotlin) use
their tools' standard configuration file names (e.g. `.golangci.yml`,
`clippy.toml`/`Cargo.toml`, `stylecop.json`, `.editorconfig`).

### C / C++

| Use case | Tool | Command / marker |
|---|---|---|
| Lint / static analysis | **clang-tidy** (config: `.clang-tidy`) | `clang-tidy -p build <files>` |
| Complementary static analysis | **cppcheck** (pattern/data-flow; no compile DB needed — catches what clang-tidy misses) (config: `cppcheck.supp`) | `cppcheck --project=compile_commands.json` |
| Bug finding (path-sensitive) | **Clang Static Analyzer** | `scan-build cmake --build build` |
| Format | **clang-format** (config: `.clang-format`) | `clang-format --dry-run --Werror .` |
| Compiler warnings | gcc/clang `-Wall -Wextra -Wpedantic`, MSVC `/W4`; gate with `CMAKE_COMPILE_WARNING_AS_ERROR` | — |
| Sanitizers (dynamic) | ASan/UBSan/TSan (built into gcc/clang) | `-fsanitize=address,undefined` / `thread` |
| Dep vuln (SCA) | **osv-scanner** (or **Trivy**) over vcpkg.json / conanfile / lockfiles | `osv-scanner scan .` |
| License | **ScanCode Toolkit** for vendored third-party trees | `scancode -clpeu --json-pp out.json .` |
| Architecture / layer rules | No mainstream free tool — a small custom include-graph check (one-off script over `#include` edges) | — |
| Tests | **GoogleTest** (or Catch2) | `ctest --test-dir build` |
| Coverage — Clang lane (incl. clang-cl) | **llvm-cov** (`-fprofile-instr-generate -fcoverage-mapping`, or clang-cl `/PROFILE`) | `llvm-profdata merge` → `llvm-cov show/report` |
| Coverage — GCC lane | **gcov + lcov** (`--coverage`) | `lcov --capture --output-file coverage.info && genhtml coverage.info` |
| Fuzzing | **libFuzzer** (built into clang), or **AFL++** | `-fsanitize=fuzzer` |
| Docs | **Doxygen** (config: `Doxyfile`) | `doxygen Doxyfile` |
| Include hygiene | **include-what-you-use** | `iwyu_tool -p build` |

Coverage is the **dynamic measurement lane** and is per toolchain — use what
the build lane uses (llvm-cov for Clang/clang-cl, gcov+lcov for GCC). A repo
with static analysis but no coverage lane is missing its refactor safety net:
run coverage before and after structural changes and treat the missing lane as
the first gap to close.

### Java (Maven/Gradle, incl. Eclipse/OSGi)

| Use case | Tool | Command / marker |
|---|---|---|
| Style / clean-code lint | **Checkstyle** (config: `checkstyle.xml`) | `mvn checkstyle:check` |
| Bug patterns | **SpotBugs** | `mvn spotbugs:check` |
| Code-smell rules | **PMD** (and PMD CPD for duplication) | `mvn pmd:check` / `mvn pmd:cpd-check` |
| Compile-time bug patterns | **Error Prone** (javac plugin) | build integration |
| Format | **Spotless** with google-java-format | `mvn spotless:check` |
| Dep vuln (SCA) | **OWASP Dependency-Check** (or **osv-scanner**) | `mvn org.owasp:dependency-check-maven:check` |
| License | **license-maven-plugin** | `mvn license:aggregate-add-third-party` |
| Architecture rules | **ArchUnit** (rules as JUnit tests) | `mvn test` |
| Tests | **JUnit 5** (Tycho surefire for OSGi) | `mvn test` / `mvn verify` |
| Coverage | **JaCoCo** | `mvn jacoco:report` |
| Mutation | **PIT** | `mvn org.pitest:pitest-maven:mutationCoverage` |
| Fuzzing | **Jazzer** (used by oss-fuzz) | `jazzer` |
| Docs | **Javadoc** (JDK built-in) | `mvn javadoc:javadoc` |
| Module hygiene | **jdeps** (JDK built-in) | `jdeps --multi-release 21 <jar>` |

### Python

| Use case | Tool | Command / marker |
|---|---|---|
| Lint (incl. import sorting) | **Ruff** (config: `pyproject.toml [tool.ruff]` or `ruff.toml`) | `ruff check .` |
| Format | **Ruff format** (alt: Black) | `ruff format --check .` |
| Types | **mypy** (alt: Pyright) | `mypy .` |
| SAST | **Bandit** (alt: Semgrep OSS) | `bandit -r .` |
| Dep vuln (SCA) | **pip-audit** (add osv-scanner for mixed repos) | `pip-audit` |
| License | **pip-licenses** | `pip-licenses --format=columns` |
| Architecture / import rules | **import-linter** (config: `.importlinter`) | `lint-imports` |
| Tests | **pytest** | `pytest` |
| Coverage | **coverage.py** via pytest-cov | `pytest --cov` |
| Mutation | mutmut (community-scale; optional) | `mutmut run` |
| Fuzzing | **Atheris** (Google, used by oss-fuzz) | `atheris` |
| Docs | **Sphinx** (or MkDocs) | `sphinx-build docs docs/_build` |

Ruff subsumes flake8/isort/pycodestyle/pyupgrade — do not stack those on top.

### TypeScript / JavaScript

| Use case | Tool | Command / marker |
|---|---|---|
| Lint | **ESLint** flat config with **typescript-eslint** (config: `eslint.config.*`) (alt: Biome — replaces ESLint + Prettier together) | `eslint .` |
| Format | **Prettier** (alt: Biome — replaces ESLint + Prettier together) | `prettier --check .` |
| Types | **tsc** | `tsc --noEmit` |
| SAST | **Semgrep OSS** (alt: eslint-plugin-security) | `semgrep --config auto` |
| Dep vuln (SCA) | **npm audit / pnpm audit** first pass, add **osv-scanner** for mixed repos | `npm audit --audit-level=high` |
| Client-side/CDN JS | **Retire.js** | `retire --path .` |
| License | **license-checker** | `license-checker --production` |
| Architecture / import rules | **dependency-cruiser** (config: `.dependency-cruiser.cjs`) | `depcruise --config .dependency-cruiser.cjs src` |
| Unit tests | **Vitest** (alt: Jest) | `vitest run` |
| E2E | **Playwright** | `npx playwright test` |
| Accessibility | **axe-core** (`@axe-core/playwright`) | in Playwright tests |
| Coverage | Vitest v8 coverage / Istanbul | `vitest run --coverage` |
| Mutation | **Stryker** | `npx stryker run` |
| Docs | **TypeDoc** | `typedoc` |

### HTML / CSS

- HTML lint: **htmlhint**. CSS lint/format-side rules: **Stylelint**.
- Formatting: **Prettier**. Accessibility: **axe-core**.
- Legacy/CDN JavaScript: **Retire.js**.

### Markdown

| Use case | Tool | Command / marker |
|---|---|---|
| Lint (structure/style) | **markdownlint-cli2** (config: `.markdownlint.yaml` / `.markdownlint.jsonc`) | `markdownlint-cli2 "**/*.md"` |
| Prose style | **Vale** (style-guide rules; complementary to markdownlint) (config: `.vale.ini`) | `vale .` |
| Format | **Prettier** (same formatter as the TS/JS stack — do not add a second) | `prettier --check "**/*.md"` |
| Link check | **lychee** (config: `lychee.toml`) | `lychee .` |
| Diagram syntax (Mermaid) | **mermaid-cli** (official Mermaid project — rendering is the syntax check) | `mmdc -i diagram.mmd -o /dev/null` (standalone `*.mmd`; extract ` ```mermaid ` fences the same way) |

Applies to all prose: README, AGENTS/instruction files, `docs/`, skill files —
including ` ```mermaid ` diagram fences and standalone `*.mmd` files.

### Config / data formats

Everything with a parser can be validated — do it in the gate.

| Format | Lint / validate | Format | Notes |
|---|---|---|---|
| YAML | **yamllint** | Prettier | CI workflows, configs |
| GitHub Actions workflows | **actionlint** (complements yamllint with workflow semantics) | — | — |
| TOML | **taplo check** (config: `taplo.toml`) | **taplo fmt** | pyproject, Cargo, CMake presets |
| JSON | **check-jsonschema** (or ajv-cli) validated against the published schema | Prettier | `opencode.json`, tsconfig, launch configs — every config that has a schema |
| XML | **xmllint** (libxml2) | — | `pom.xml`, Android manifests, feature.xml |
| SQL | **sqlfluff** | **sqlfluff fix** | — |
| HCL / Terraform | **tflint** (plus **Checkov** for policy) | **terraform fmt** | — |
| dotenv | **dotenv-linter** | dotenv-linter `--fix` | `.env*` files |

Each format gets exactly one formatter (the Format column); linting and schema
validation are complementary layers on top.

### LaTeX

- Lint: **ChkTeX** (alt: lacheck). Indent/format: **latexindent**.
- Build: **latexmk**. Spell check: hunspell/aspell.

### CMake

- Format/lint: **cmake-format** / cmake-lint (light maintenance — rely mainly on
  strict compiler warnings in the compiled languages for code quality).
- Treat CMake scripts as build code: run the same review bar as sources.

### PowerShell / Shell

- PowerShell: **PSScriptAnalyzer** (`Invoke-ScriptAnalyzer -Path .`, formatter:
  `Invoke-Formatter`), tests with **Pester**.
- Shell: **shellcheck** (lint) + **shfmt** (format), tests with **bats-core**.

## Cross-cutting (any language)

| Use case | Tool | Note |
|---|---|---|
| Secrets in git history | **gitleaks** (alt: TruffleHog) | `gitleaks detect` |
| Dockerfile | **hadolint** | — |
| IaC (Terraform/K8s) | **Checkov** (or Trivy) | only where IaC exists |
| Editor formatting baseline | **EditorConfig** (`.editorconfig`) | free, universal |
| Multi-language hooks | **pre-commit** | one hook framework for all of the above |
| CI aggregator | **MegaLinter** (or Super-Linter) | runs the whole matrix in one CI job |
| SBOM | **Syft** (or cdxgen) | CycloneDX/SPDX output |
| GitHub-native (free) | **Dependabot**, **dependency-review-action** | both free; **CodeQL** is free for public repos only |
| Container/manifest scan | **Trivy** (alt: Grype) | SCA + IaC + secrets in one binary |

## Dependency-vulnerability quick picker

| Ecosystem | First choice | Cross-ecosystem layer |
|---|---|---|
| npm / pnpm / yarn | `npm audit` / `pnpm audit` (bundled) | osv-scanner |
| Python | pip-audit (PyPA) | osv-scanner |
| Java/Maven | OWASP Dependency-Check | osv-scanner / Trivy |
| Go | govulncheck (call-graph reachability) | osv-scanner |
| Rust | cargo-audit + cargo-deny (RustSec) | Trivy |
| .NET | `dotnet list package --vulnerable` | Trivy |
| C/C++ | osv-scanner over vcpkg/Conan manifests | Trivy |
| Anything, or mixed repos | **osv-scanner** (OSV.dev matching, low false positives) | — |

Ecosystem-native scanners give the fewest false positives in their own
ecosystem; osv-scanner is the reliable free layer across mixed stacks.

## Usage rules

1. **Exactly one formatter per language.** Formatters are opinionated and
   mutually exclusive: never run two (clang-format alone, Prettier alone,
   Ruff format *or* Black, ktlint alone).
2. **Static analyzers combine — that is the point.** Tools that cover
   *different detection classes* belong together in one gate:
   - C/C++: clang-tidy (AST-based, compile-DB) + cppcheck (pattern/data-flow
     analysis, works without a full compile) + compiler warnings + sanitizers.
     Each catches classes the others miss.
   - Java: Checkstyle (style) + SpotBugs (bug patterns) + PMD (code smells) +
     Error Prone (compile-time patterns) — four different classes, all fine.
   - Python: Ruff (style) + mypy (types) + Bandit (security) — same idea.
   - TS/JS: ESLint (style/correctness) + tsc (types) + Semgrep (security).
3. **Never stack near-duplicates.** One tool per category: Ruff replaces
   flake8/isort/pycodestyle; clang-format replaces uncrustify/AStyle; Biome
   replaces ESLint+Prettier together if you adopt it. Choose one stack, not
   two tools doing the same job. Alternatives in a cell (`or`, `alt:`) are
   mutually exclusive.
4. Wire checks into the project's existing gate (verify target / CI job /
   pre-commit), not as a parallel script nobody runs. If the repo has no gate
   yet, create one entry point (a `verify` script/target) and wire everything
   into that.
5. Where the matrix says "—", skip the use case; do not adopt a weak tool to
   fill the cell.

## Workflow: find the gaps and add the missing tools

When run against a repo, this skill audits the repo against the matrix and
closes the gaps. The matrix is the checklist; the per-language tables above
give the config markers to look for.

1. **Detect the repo's languages** from its manifest/build files
   (`CMakeLists.txt`, `pom.xml`/`build.gradle`, `pyproject.toml`/
   `requirements.txt`, `package.json`, `*.tex`, `*.ps1`, `*.sh`, …) **and its
   data files by extension** (`*.md`, `*.yml`/`*.yaml`, `*.toml`, `*.json`,
   `*.xml`, `*.sql`, `*.tf`, `*.mmd`, `.env*` — the Markdown and Config/data rows apply
   whenever such files exist). A build
   system's own scripts are their own row: a CMake project covers the C/C++
   rows *and* the CMake row.
2. **Scan each language's matrix row** and classify every use case:
   *covered* (tool configured **and** wired into the gate), *missing*, or
   *not applicable*. Use the config markers from the detail tables
   (`.clang-tidy`, `[tool.ruff]`, `eslint.config.*`, `checkstyle.xml`,
   `.dependency-cruiser.cjs`, test/coverage wiring, …); for languages without
   a detail table, use the tool's standard config file names.
3. **Report the gap list first**, before changing anything.
4. **Add the missing tools — core use cases first**: lint, format,
   static/type analysis, dependency-vulnerability (SCA), tests, coverage.
   For each:
   - install/configure it the ecosystem-standard way (declared dev
     dependency + its config file in the repo's idiom);
   - wire it into the repo's **existing** gate entry point (verify target,
     CI job, pre-commit) — never a parallel script;
   - if the codebase would flood findings with a new analyzer, introduce it
     against a **recorded baseline** of pre-existing findings (listed as debt,
     with the intent to burn it down), then ratchet to failing on new findings.
   Optional use cases (mutation, fuzzing, license, SBOM, docs, secrets) are
   added only when the project actually calls for them.
5. **Verify by command**: run each newly added tool and the repo's gate. With a
   baseline in place, the tool must pass against that baseline. The change is
   done only when a command proves it. Re-scan the row — the core use cases
   must come back *covered*.
6. **Never weaken what exists**: findings are fixed at source; no silent
   suppressions and no relaxed rules. The only allowed shadow over a finding is
   the explicit, recorded baseline from step 4 (debt with a plan), never a
   permanent silence. Existing repo conventions (AGENTS.md / QUALITY.md) refine
   how tools are wired, not whether they apply.
