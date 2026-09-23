---
name: cpp-tools
description: >
  Use this skill as the methodology/knowledge layer for the cpp-tools agent:
  how to drive CMake configure/build, clang-format, cppcheck, clang-tidy,
  clang-scan-deps, and how to read their outputs and reports. The agent
  performs the bash actions; this skill keeps them correct and consistent.
  Not part of the software lifecycle — it is the C++ execution utility.
---

# C++ Tools — Methodology

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the cpp-* domain set; standalone (no lifecycle pair).

You are a pragmatic C++ tooling partner for the Hephaestus `cpp-tools` agent.

Your job is to keep C++ configure / build / format / static-analysis actions
correct, reproducible, and legible. The `cpp-tools` *agent* runs the bash
commands; this skill is the method/knowledge layer that says *what* to run
and *how to interpret it*.

## Position

This is a **standalone, on-demand** C++ utility. It is **not** part of the
software lifecycle and has no skill pair; invoke it whenever C++ code in this
repo needs to be built, formatted, or analyzed.

## Scope

This skill **owns**: the canonical invocations for CMake, clang-format,
cppcheck, clang-tidy, clang-scan-deps, and the reading of their reports. It
does not write feature code (that is `software-implementation`).

## Core Principles

1. Configure once, build in place; keep build artifacts out of source control.
2. Format is non-negotiable: clang-format must pass before analysis.
3. Run cppcheck + clang-tidy; triage findings by severity.
4. Treat warnings as errors in CI-like runs; report the file:line for every finding.
5. Read reports from the agent's output; never guess tool paths.

## Canonical actions (run by the agent)

- **Configure:** `cmake -S . -B build -DCMAKE_BUILD_TYPE=Release`
- **Build:** `cmake --build build --config Release -j`
- **Format (check):** `clang-format --dry-run --Werror $(find . -name '*.cpp' -o -name '*.h')`
- **Format (apply):** `clang-format -i <files>`
- **Static analysis:** `cppcheck --enable=all --project=build/compile_commands.json`
  and `clang-tidy -p build <files>`
- **Dependency graph:** `cmake --build build --target scan-deps` (writes
  `build/reports/scan-deps/deps.json`); ad-hoc:
  `clang-scan-deps -compilation-database=build/compile_commands.json --format=experimental-full > deps.json`
- **Reports:** read the agent's stdout/stderr (and any `build/reports/*.txt`)
  for per-file:line findings; summarize by severity.

## Quality contract (the gate)

- **Lane exit codes** (every `tools/check-*.sh`): `0` clean, `1` findings,
  `77` toolchain missing. In ctest the lanes register with
  `SKIP_RETURN_CODE 77` and the `analysis` label (`ctest -L analysis` runs
  only them, `ctest -LE analysis` skips them); a missing tool is a SKIP,
  never a failure. A lane's exit code is the gate - not the wrapping build's.
- **Fail vs report-only:** correctness findings fail - compiler warnings as
  errors, `clang-diagnostic-*`, `clang-analyzer-*`, `bugprone-*`, cppcheck
  warning/performance/portability, layer violations, sanitizer aborts, test
  failures. Modernization (`modernize-*`), style and benchmark numbers are
  report-only guidance: generated code is guided, not blocked.
- **Sanitizers:** one dedicated build tree per lane
  (`-DENABLE_SANITIZER=address,undefined` or `thread`); sanitized and plain
  objects never mix. TSan suppressions live in `tools/tsan.supp`, short and
  justified - deadlocks are never suppressed.
- **Third-party scope:** vendored/fetched sources are not ours to gate -
  they are excluded from warnings-as-errors and every analyzer (the
  CMakeLists clears `CXX_CLANG_TIDY`/`CXX_CPPCHECK` on fetched targets).
- **Tests - dual oracle:** assert on internal state, not only on output.
  Golden files catch rendering; state assertions catch what pixels cannot
  prove (silently skipped spawns, unwritten saves, invariant drift). A
  golden mismatch must print the diff count and the first differing point.
- **Parked gates:** a gate that cannot be green is parked - removed from the
  default run with a written rationale, kept directly runnable. A
  permanently red gate breeds alarm fatigue and hides real failures.
- **Noise floor first:** before disabling any check, measure and record the
  hit count that justifies it. A disable without a measurement is a guess.
- **Honest gaps:** every test plan carries a "what is NOT covered" list with
  reasons - coverage is auditable only when its holes are named.

## Reading the result

- Build failure -> report the first error's file:line + the command that failed.
- clang-format diffs -> list the files needing `clang-format -i`.
- cppcheck/clang-tidy -> group findings (error/warning/style) per file; fix
  errors first, then warnings, then style.
- clang-scan-deps (`reports/scan-deps/deps.json`, `--format=experimental-full`)
  -> one JSON document: `translation-units[]` (each with
  `commands[0].input-file` and `commands[0].file-deps` — the exact include set
  that TU's preprocessor read) plus `modules[]` (empty until the project
  adopts C++23 modules; the same scanner feeds CMake's dyndep then).
  Third-party TUs (e.g. GoogleTest's `gtest-all.cc`) appear because they are
  in the compilation database — filter by path prefix when only first-party
  deps matter. Ad-hoc formats: `--format=make` (per-TU Makefile fragments),
  `--format=p1689` (P1689 module rules, for module bring-up).

## Notes / Hand Off

- Feature code changes belong to `software-implementation`.
- When a rendered result needs comparison, use `graphics-render-comparison`.
