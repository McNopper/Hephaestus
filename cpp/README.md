# AI-first C++23 build template

## About this document
- **Kind:** `doc` / C++ subtree README
- **Read by:** humans adopting the C++ skeleton; **written by:** maintainers
- **Related:** pairs with `cpp/AGENTS.md` (command manifest)

A lean, modern C++23 project skeleton whose tooling is configured to emit
**structured, machine-readable information** that an AI agent can use to
navigate, refactor, and verify the code — while staying out of the way when
code is being generated.

The guiding idea: the static analysis is here so an agent can **detect real
problems fast in otherwise well-written code**, not to punish generation with
subjective style gates.

## Layout

```
cpp/
├── CMakeLists.txt          # build + analysis wiring
├── CMakePresets.json       # environment-conditional presets (Windows: default/release/clang64/mingw64/windows/analysis; Linux+WSL: linux)
├── AGENTS.md               # canonical command manifest for agents
├── .clang-format           # formatting rules
├── .clang-tidy             # high-signal, low-friction check set
├── .gitignore              # ignores build*/ trees
├── cppcheck.supp           # cppcheck suppressions
├── Doxyfile.in             # AI-oriented Doxygen config (XML + tagfile)
├── cmake/
│   ├── cppcheck.cmake      # single source of truth for cppcheck targets
│   └── clang-scan-deps.cmake # single source of truth for the scan-deps target
├── include/
│   └── example.hpp
├── src/
│   ├── example.cpp
│   └── main.cpp
└── tests/
    └── example_test.cpp
```

## Quick start

```bash
cmake --list-presets                        # shows exactly what THIS environment can build
cmake --preset default                       # configure (Debug + analysis)
cmake --build build --target verify          # fast: build + test + analysis status
cmake --build build --target verify-full     # full: verify + format + static analysis + docs

# MSYS2 toolchains (run from the matching shell so clang/gcc+ninja are on PATH):
cmake --preset clang64 && cmake --build build-clang64   # CLANG64: full analysis gate
cmake --preset mingw64 && cmake --build build-mingw64   # MINGW64: gcc build

# Windows host without Clang/Ninja - MSVC via the Visual Studio generator:
cmake --preset windows                       # analysis is skipped for this toolchain
cmake --build build-windows --target verify

# Linux / WSL (from inside the distro; the Windows presets hide automatically):
cmake --preset linux && cmake --build build-linux && ctest --preset linux
```

Every preset has its own `build*` directory, so all toolchains can be
configured **in parallel** on the same checkout without clobbering each other.

See **[AGENTS.md](AGENTS.md)** for the full command manifest and the locations
of every machine-readable report.

## C++ execution (cpp-tools agent)

C++ work in this repo is driven by the `cpp-tools` **agent** (methodology in the
`cpp-tools` skill). It runs the CMake targets via bash and reads the machine-readable
reports. There is no separate MCP server — C++ is an agent now.

## Toolchain

- **Recommended:** Ninja + a Clang- or GNU-compatible compiler. This exports
  `compile_commands.json` and enables the full clang-tidy / cppcheck stack.
  Presets `default`, `release`, and `analysis` target this path (Ninja + Clang).
- **Windows / MSVC:** the `windows` preset uses the Visual Studio 18 2026
  generator + MSVC (the default Visual Studio version for this repo). The
  project configures and builds normally; the Clang-based analysis is skipped
  (CMake prints a status line saying so). Use this when Clang/Ninja are not
  available. To target a different Visual Studio, change the preset's
  `generator` to its `<major> <year>` string (e.g. `Visual Studio 17 2022`) —
  CMake requires the year in the generator name, so it cannot be left
  unversioned.
- **Other toolchains still build.** With a generator that has no compile
  database, the project configures and builds normally and the Clang-based
  analysis is skipped (CMake says so at configure time). This is by design,
  expressed as a positive allowlist rather than blocking any specific compiler.

**External tools must be on PATH:** [CMake](https://cmake.org/download/) (≥ 3.26),
[Ninja](https://ninja-build.org/), [cppcheck](https://github.com/danmar/cppcheck), and
the LLVM/Clang tools (`clang-format`, `clang-tidy`, `clang-scan-deps`).

## What makes it "AI-first"

- **`compile_commands.json`** exported for every analysis tool and editor.
- **Doxygen XML + tagfile** (`build/docs/xml/`, `build/docs/MyProject.tag`) —
  machine-readable structure and a compact symbol index, alongside HTML.
- **clang-tidy fix export** (`build/reports/clang-tidy/fixes.yaml`) — findings
  *and* suggested edits an agent can apply.
- **cppcheck XML** (`build/reports/cppcheck/cppcheck.xml`).
- **clang-scan-deps dependency graph** (`build/reports/scan-deps/deps.json`) —
  the exact include set of every translation unit (and module provides/requires
  once C++23 modules arrive), scanned with each TU's real compile flags.
- **analysis status contract** (`build/reports/analysis-status.txt`) with
  `analysis=enabled|skipped` and toolchain reason.
- Stable, predictable report paths under `${binaryDir}/reports/`.
- Two verification levels: fast **`verify`** and strict **`verify-full`**.

## Analysis philosophy

`.clang-tidy` errors only on correctness-oriented checks
(`clang-diagnostic`, `clang-analyzer`, `bugprone`). `performance`,
`portability`, and modernization checks stay warnings. Friction-causing checks are
deliberately disabled: magic numbers, identifier length, brace/function-size
mandates, swappable parameters, `bugprone-exception-escape` (a known platform
false positive), and `portability-avoid-pragma-once`.

cppcheck runs a high signal-to-noise default profile
(`warning,performance,portability`); an exhaustive `cppcheck-strict` profile
(`all + inconclusive`) is available opt-in.

## Renaming the project

Replace `MyProject` / `my_project_*` in `CMakeLists.txt` and the `example`
sources with your own names; everything else adapts automatically.

## Third-party licenses

Tests use [GoogleTest](https://github.com/google/googletest) (v1.17.0,
**BSD-3-Clause**), fetched on demand via CMake `FetchContent` when
`ENABLE_TESTING=ON` (the default). It is not checked into this template;
consumers fetch it themselves during configure. Doxygen (when
`ENABLE_DOXYGEN=ON`) is invoked via `find_package` as a system build tool and is
not redistributed.
