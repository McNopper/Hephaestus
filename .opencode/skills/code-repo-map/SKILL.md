---
name: code-repo-map
description: >
  Use this skill to build a compact repository map for fast orientation in an
  unfamiliar codebase: top-level layout, build/test entry points, key packages or
  modules with one-line responsibilities, and where configuration lives. It samples
  the tree with cheap glob/grep probes instead of reading files exhaustively, and
  emits a single Markdown map other agents or humans can consume as shared context.
  Use it when onboarding a fresh session, starting a ticket in an unfamiliar area,
  or when a task brief says "orient first". Pairs with code-dependency (which adds
  the dependency edges between the nodes this skill locates).
---

# Code Repo Map Skill

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** own `code-` domain; pairs with `code-dependency` (map = where things are, dependency skill = how they connect); consumed by fleet workers as step 0 of a ticket in unfamiliar territory.

You produce a **repository map**: the minimal structural knowledge an agent needs
before touching code — what the parts are, where they live, and how to build/verify.
You deliberately stay **shallow and cheap**: probes, not reads.

## Scope

This skill **owns**:

- discovering the top-level layout (directories, build systems, module lists),
- locating build, test, lint, and config entry points,
- one-line responsibility statements for each key module/package,
- emitting the map as a single Markdown document or chat section.

This skill **does not**:

- read entire files or explain implementations,
- map dependencies or detect cycles (that is `code-dependency`),
- change code — it only reads and reports.

## Method (probe, don't read)

1. **Shape pass** — list the top two directory levels; note README/AGENTS/build
   files (`build.ps1`, `CMakeLists.txt`, `package.json`, `pom.xml`, …). Read ONLY
   the README/AGENTS headers if present, nothing more.
2. **Build pass** — identify from filenames, without executing: configure command,
   build command, test command, lint command. Quote them verbatim from docs if
   stated; otherwise infer conservatively and mark inference with `(inferred)`.
3. **Module pass** — for each top-level source container (bundle/package/src dir),
   determine its responsibility from name + at most 3 sampled filenames + a single
   targeted grep for package/class declarations. One line each.
4. **Signal pass** — one grep each for: entry points (`main(`, `Activator`,
   `@Component`), test locations (`*Test`), generated/vendored dirs to SKIP.
5. **Emit** — the map (format below). If the caller asked for a file, write it
   (e.g. `docs/repo-map.md`); otherwise reply with it inline.

## Output format

```markdown
# Repo map: <name> (<date>, commit <short-sha>)
Build: <configure+build commands> · Test: <test command> · Verify gate: <command>
## Layout
| Path | What it is (one line) |
## Entry points
- <file:line — what starts here>
## Tests live in
- <patterns/dirs>
## Skip these
- <generated/vendored/binary dirs>
## Orientation notes
- <at most 5 bullets: conventions, gotchas, cross-cutting rules>
```

Rules: never exceed ~1 screen per section; every claim carries a path; `(inferred)`
marks anything not read directly; a stale map beats no map — note the commit sha so
consumers can judge freshness.
