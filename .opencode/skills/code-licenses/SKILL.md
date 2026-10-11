---
name: code-licenses
description: >
  Use this skill to audit the licensing of a codebase: detect the project's own
  license, enumerate third-party libraries across language ecosystems (C++, Python,
  Node, Rust, Go, .NET, and vendored sources), resolve each dependency's license to
  an SPDX id, judge compatibility against the project license, and emit a Markdown
  report with a per-library table plus prioritized remediation. Supports two scope
  modes — a **full** list (every dependency derived from the codebase) or a
  **newly-introduced** list (only deps this repo adds on top of an underlying
  library it builds on); **default is newly-introduced**. Read-only; not part of any
  software lifecycle. Pairs with code-dependency (which maps dependencies without
  the legal/license angle).
---

# License Audit Skill

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the `code-` domain; pairs with `code-dependency` (which maps
  dependencies but ignores licenses). Standalone (no lifecycle pair).

You are a pragmatic license-compliance auditor for a codebase.

Your job is to inventory third-party libraries, resolve their licenses, judge
compatibility with the project's own license, and report concrete remediation so the
repository is clean from a license-file perspective.

## Position

This is a **standalone, on-demand** code-analysis utility. It is **not** part of the
software lifecycle and has no skill pair in the definition/verification set; invoke it
whenever a codebase needs a license audit, a pre-release compliance check, or an
answer to "are my dependencies safe to use under our license?".

## Scope

This skill **owns**:

- Detecting the project's own license (root `LICENSE`/`COPYING`/`NOTICE`, manifest
  `license:` fields, SPDX headers). This is the reference for every verdict.
- Enumerating direct third-party dependencies across ecosystems (see Step 2).
- Choosing the **scope mode** — full list vs newly-introduced-only (see Step 2b;
  default: newly-introduced).
- Resolving each dependency's license to an SPDX id.
- Producing a per-library compatibility verdict against the project license.
- Detecting repository hygiene gaps (missing `LICENSE`/`NOTICE`, missing attribution,
  missing SPDX headers, no aggregated third-party notices).
- Emitting a Markdown report with a dependency table and prioritized actions.

This skill **does not**:

- Provide legal advice — it is methodology, not a lawyer.
- Modify code, manifests, or vendored files (read-only). Restoration of missing
  license files is handed off to `software-implementation`.
- Chase transitive (indirect) dependencies by default — opt-in only (can be huge).
- Invent licenses — an unresolvable license is flagged `unknown`, never guessed.

## Core Principles

1. **Detect the project license first.** Every compatibility verdict is relative to it;
   if it cannot be determined, say so and ask the human rather than assuming.
2. **SPDX ids everywhere.** Use SPDX license identifiers (`MIT`, `Apache-2.0`,
   `GPL-3.0-only`, `BSD-3-Clause`, …) — never free-form license names in the table.
3. **Resolution order:** manifest field (most authoritative) → vendored `LICENSE` file →
   network/registry lookups (opt-in) → header-scan fallback. Prefer offline, fast signals.
4. **Classify, then judge.** Each license falls into exactly one bucket:
   permissive · weak copyleft · strong copyleft · network copyleft · proprietary · unknown.
5. **Flag unknowns explicitly.** `unknown` is a result, not a failure — surface it for review.
6. **Network access is opt-in** and may fail offline. Always state when you used it.
7. **Read-only:** report and recommend; never write fixes into the tree.
8. **Pick a scope mode up front (default: newly-introduced).** Decide whether the
   report lists *every* dependency (full) or only those this repo introduces on top
   of an underlying library it reuses (newly-introduced). See Step 2b.

## Step 1 — Detect the project license

Search, in order:

1. Root `LICENSE` / `LICENCE` / `COPYING` / `NOTICE(.md|)` — read the first lines; map the
   text/header to an SPDX id (see the resolution hints in Step 3).
2. Manifest `license` fields: `package.json` (`"license"`), `pyproject.toml`
   (`[project] license` / `classifiers`), `setup.cfg` / `setup.py`, `Cargo.toml`
   (`license =`), `*.csproj` (`<PackageLicenseExpression>`), `vcpkg.json` (`"license"`),
   `conanfile.py` (`license`).
3. SPDX-License-Identifier headers in source (`grep -R "SPDX-License-Identifier"`).
4. If still ambiguous → report the candidate(s) and **ask the human**. Never silently default.

Record the detected id and *where* it came from (so the verdict is auditable).

## Step 2 — Enumerate dependencies per ecosystem

Read the relevant manifests and record each dependency with its source + version.

| Ecosystem | Manifests / signals to read | Field / signal |
|---|---|---|
| C++ | `CMakeLists.txt` (`find_package`, `FetchContent`, `CPM`, `target_link_libraries`); `vcpkg.json` (`"dependencies"`); `conanfile.txt`/`.py` (`[requires]`); `*.cmake` | name + version |
| Python | `requirements*.txt`, `pyproject.toml` (`[project.dependencies]`), `setup.py`/`setup.cfg`, `Pipfile`, `poetry.lock`, `uv.lock` | `name == version` |
| Node | `package.json` (`dependencies`, `devDependencies`), `package-lock.json`, `pnpm-lock.yaml`, `yarn.lock` | `name: version` |
| Rust | `Cargo.toml` (`[dependencies]`/`[dev-dependencies]`), `Cargo.lock` | `name = version` |
| Go | `go.mod` (`require`), `go.sum` | `module/name version` |
| .NET | `*.csproj` (`<PackageReference>`), `packages.config` | `Include="name" Version=".."` |
| Vendored | `third_party/`, `vendor/`, `3rdparty/`, `extern/`, `libs/`, `external/` — any dir with its own `LICENSE` | name from folder/README |

> By default enumerate **direct** dependencies. Transitive resolution is **opt-in**
> (see Step 3 tools) — call it out when enabled, because counts and conflicts explode.

## Step 2b — Scope mode: full vs newly-introduced

Before emitting the report, choose how much of the dependency set to surface. This
matters when the audited repo **builds on top of an underlying library** that
already bundles its own third-party code (e.g. an examples repo reusing a helper
library, or an app reusing a shared framework): the boilerplate library owns the
licenses of the deps *it* pulls in, so re-listing them in the child repo is noise.

Two modes:

- **`full`** — list **every** dependency derived from the codebase (Step 2 + opt-in
  transitive). Use this for a self-contained library/framework, or when you want the
  complete picture regardless of ownership.
- **`newly-introduced`** *(default)* — list **only the dependencies this repository
  adds on top of the underlying library it reuses**. The underlying library's own
  dependencies are *not* re-listed; instead, reference that library's own
  third-party notices by **absolute URL** (so the report stands alone on GitHub /
  any fork) and note that the child repo complies via it.

**How to apply `newly-introduced` (default):**

1. Detect whether the repo reuses an underlying library (look for: a sibling/local
   checkout pulled via `FetchContent`/`SOURCE_DIR`/`add_subdirectory`; a framework
  /`package` the repo treats as its base; a "based on X" statement in README).
2. Enumerate the deps **this** repo fetches/declares that the underlying library does
   **not** already own.
3. In the report, state the underlying library + its license, link to its
   third-party notices with an **absolute URL**, and list only the child repo's
   *additional* deps (plus any bundled non-code assets, which the underlying library
   does not cover).
4. If no underlying library is detected, `newly-introduced` degrades gracefully to
   `full` (everything is "newly introduced" by this repo).

> Always record which mode was used in the report header so the scope is auditable.

## Step 3 — Resolve each dependency's license

For each dependency, resolve to one SPDX id in this order:

1. **Manifest SPDX field** — npm/pip poetry/cargo often carry a license expression directly.
2. **Vendored `LICENSE` file** — read the vendored directory's `LICENSE`/`COPYING`; map the
   header/short name to an SPDX id. This is authoritative for vendored code.
3. **Opt-in registry/tool lookups** (only when the user wants a deep audit):
   - Python: `pip-licenses` (emits a table incl. license + author), or `pip show <pkg>`.
   - Node: `npx license-checker` / `npm ls` + `npm view <pkg> license`.
   - Rust: `cargo about generate` / `cargo license`.
   - Go: `go-licenses report ./...` (needs the module built).
   - .NET: `dotnet-project-licenses` / NuGet `license` metadata.
   - Generic file scan: `askalono` / `licensecheck` over a tree (useful for vendored dirs).
   State clearly when network/registry access was used and that results may differ offline.
4. **Header-scan fallback** — `grep -R "SPDX-License-Identifier"` inside the dependency's
   source when no manifest/LICENSE is present.

If none resolve → mark `unknown` and list the evidence gap in the report's gaps section.

## Step 4 — Compatibility verdict

Classify each resolved dependency license, then judge against the **project** license.

| Bucket | Examples (SPDX) | Meaning |
|---|---|---|
| Permissive | `MIT`, `BSD-2-Clause`, `BSD-3-Clause`, `ISC`, `Zlib`, `Apache-2.0`, `Unlicense`, `0BSD`, `MIT-0` | Allows use in any project; mostly requires **attribution** (retain copyright + license text). Apache-2.0 also needs `NOTICE` retention + patent grant. |
| Weak copyleft | `LGPL-2.1-only`, `LGPL-3.0-only`, `MPL-2.0` | Source changes to the *library* must be shared; usually fine when **linked/used** without modifying. MPL-2.0 is file-level. |
| Strong copyleft | `GPL-2.0-only`, `GPL-3.0-only`, `GPL-3.0-or-later` | Derivative works must be distributed under the **same** license. Conflict if the project is more permissive. |
| Network copyleft | `AGPL-3.0-only` | Like GPL **plus** network-use triggers source distribution. Conflict with almost any proprietary/permissive-but-closed project. |
| Proprietary | `LicenseRef-*` / no OSS license | Use governed by vendor terms; needs manual review / may forbid redistribution. |
| Unknown | — | Could not be resolved; treat as a gap to close, never as "safe". |

**Hard rules (apply per dependency vs the project license):**

- Project is **permissive** (e.g. MIT/Apache/BSD): any permissive dep is ✅ compatible;
  weak copyleft is usually ✅ (unmodified use); **strong copyleft / AGPL is ❌ conflict**
  (would force the project to inherit copyleft). Attribution (NOTICE) is still required → a
  hygiene gap if missing.
- Project is **weak copyleft** (LGPL/MPL): permissive ✅; same family ✅; strong copyleft/
  AGPL ❌ unless the project itself adopts it.
- Project is **strong copyleft** (GPL): permissive/weak ✅ (GPL permits combining with
  more-permissive code); AGPL is ⚠️ (GPL ↔ AGPL one-way: AGPL code cannot go into GPL
  without compliance care).
- Project is **proprietary/closed**: every dep must be permissive-with-attribution or have
  explicit redistribution rights; copyleft deps are ❌ unless the dep is used
  unmodified and the license permits it (verify per-case).
- **Any unknown** → ❓, listed as a gap regardless of project license.

Map the verdict to an icon in the table: ✅ compatible · ⚠️ review (copyleft nuances) ·
❌ conflict · ❓ unknown.

## Step 5 — Repository hygiene checklist

Walk this list; every miss becomes a `GAP-00x` in the report.

- [ ] Root `LICENSE` present and matches the detected project license.
- [ ] Each **vendored** dependency still carries its own `LICENSE`/`COPYING` (redistribution
      of vendored OSS legally requires it).
- [ ] `NOTICE` file present when any dependency is `Apache-2.0`/`BSD-3-Clause`+ (attribution).
- [ ] SPDX-License-Identifier headers in first-party source (good hygiene; not always mandatory).
- [ ] An aggregated third-party notices file (`THIRD-PARTY.md` / `NOTICES`) listing every dep,
      its version, license, and copyright — many permissive licenses *require* this text.
- [ ] No copyleft `LICENSE` accidentally committed into a more-permissive project without review.

## Default Output

```md
# License Audit: <project / subtree>

> Scope mode: `newly-introduced` (default) | `full`   -- record which was used

## Project license
- Detected: `<SPDX>` — from `<LICENSE file | manifest field | SPDX header>`.
- Note: <any caveat, e.g. "Apache-2.0 requires NOTICE retention">.

## Dependency inventory   (mode: newly-introduced — only what THIS repo adds)

> Built on <UnderlyingLib> (<SPDX>, by <author>) — its bundled deps (glfw, glew,
> cgltf, stb) and their full notices live in <absolute URL to that lib's THIRD-PARTY.md>.
> Not re-listed here; comply via that file.

| Library | Version | Source | Detected license (SPDX) | Verdict | Notes |
|---|---|---|---|---|---|
| McNopper/EGL | — | CMakeLists.txt | MIT | ✅ compatible | added by this repo |
| albert-einstein model | — | Binaries/ | CC-BY-4.0 | ✅ compatible (attribution) | bundled asset; credit required |

## Dependency inventory   (mode: full — every dependency)

| Library | Version | Source | Detected license (SPDX) | Verdict | Notes |
|---|---|---|---|---|---|
| glfw | 3.4 | vcpkg.json | Zlib | ✅ compatible | permissive |
| imgui | 1.90 | vendored (third_party/) | MIT | ✅ compatible | retain copyright notice |
| foo-lib | 0.2 | Cargo.toml | GPL-3.0-only | ❌ conflict | strong copyleft vs MIT project |
| bar-lib | 1.1 | package.json | ❓ | ❓ unknown | no license field, no LICENSE found |

## Summary
- Scope mode: `newly-introduced` | `full` (as chosen above).
- Total deps listed: N (direct: M; transitive: K if opt-in). <If newly-introduced:
  "plus the underlying library's deps, covered via <absolute URL>.">
- ✅ Compatible: N · ⚠️ Review: N · ❌ Conflict: N · ❓ Unknown: N.

## Compliance gaps
- GAP-001: vendored `imgui` is missing its LICENSE file (MIT requires retention on redistribution).
- GAP-002: no root NOTICE file though Apache-2.0 deps require attribution.
- GAP-003: `bar-lib` license could not be resolved from manifest or source.

## Recommended actions
1. **Critical:** remove/replace GPL-3.0 `foo-lib` OR relicense the project to GPL-3.0-or-later.
2. **High:** restore LICENSE files for all vendored dependencies.
3. **Medium (mode: newly-introduced):** add a `THIRD-PARTY.md` that (a) links the
   underlying library's own third-party notices by **absolute URL** and (b) carries only
   this repo's *additional* deps + bundled assets. Do **not** duplicate the underlying
   library's deps.
   **Medium (mode: full):** add a `THIRD-PARTY.md` aggregating *every* dep's attribution
   (e.g. via `pip-licenses`/`license-checker`).
4. **Low:** add SPDX-License-Identifier headers to first-party source.
```

## Tools you may use (all opt-in; state when used)

- `pip-licenses`, `npm license`/`license-checker`, `cargo about`/`cargo license`,
  `go-licenses`, `dotnet-project-licenses` — resolve manifests + transitive deps.
- `askalono`, `licensecheck` — scan a directory for license texts (good for vendored trees).
- `pip show`, `npm view`, `cargo info` — single-package lookups when offline manifests lack a field.
- Plain `grep`/`Read` over `LICENSE` files and SPDX headers — the default, offline path.

## Notes / Hand Off

- **License conflicts** that need a relicense-or-replace decision → the human (PM escalates
  only genuinely human-worthy calls; methodology stays here).
- **Restoring missing LICENSE files** in vendored deps → `software-implementation`.
- **The non-legal dependency map** (structure, cycles) → `code-dependency`.
- This skill is **read-only**; any fix lives in a normal worker flow, not here.

## Important caveat

This skill is **methodology, not legal advice**. For production, distribution, or
high-stakes decisions, confirm findings with a qualified person. The compatibility rules
above are pragmatic defaults, not a substitute for legal review.
