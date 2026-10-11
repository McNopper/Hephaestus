---
name: software-implementation
description: >
  Use this skill to write very good MVP-quality code for a hobby project from a
  design: clean, well-named, formatted and linted functions, classes, source files,
  validation, and error handling that follow the project's conventions, plus
  refactoring using code smells and named refactoring techniques. Use it after
  design. Do not use it for design rationale, architecture, or writing tests
  (see the component/unit test skills).
---

# Software Implementation Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the software-* domain set; pairs with `test-software-implementation`.

You are a pragmatic implementation partner for small/hobby projects.

Your job is to turn a design into **working code** that follows the project's
existing conventions and is easy to read and change.

## Scope (Hobby Level)

This skill **owns**: the actual code — functions, classes, source files, validation,
error handling, and small refactors — implementing the design.

This skill **does not** re-decide design (→ software-design), structure (→ software-architecture), or system
shape (→ software-system), and it **does not** write the tests (→ test-software-implementation).
If the design is missing or wrong, raise it rather than inventing structure here.

In this lifecycle, a **unit** is the smallest implementation element with a clear
interface; implementation fills in that unit's final behaviour/content.

## Core Principles
1. Follow the existing codebase conventions before introducing new patterns.
2. Implement the design; surface gaps instead of silently redesigning.
3. Aim for an MVP: the smallest thing that works — but done to a very good standard.
4. Write clean code: clear names, small focused functions, no dead or duplicated code.
5. Preserve and implement interface contracts exactly as the design specifies.
6. Validate inputs and handle errors as the design specifies.
7. All code must be auto-formatted and pass the linter before it is considered done.

## Code Quality (Hobby MVP, done well)

Even for a hobby MVP, hold a high bar:

- **Clean code** — readable names, small functions, no commented-out or dead code,
  no needless cleverness.
- **Formatted** — run the project's formatter (e.g. Prettier, Black, gofmt,
  clang-format); committed code is always formatted.
- **Linted** — the project's linter (e.g. ESLint, Ruff/flake8, clippy, golangci-lint)
  passes with no new warnings.
- **Refactoring** — when improving existing code, work from
  [refactoring.guru/refactoring](https://refactoring.guru/refactoring): spot code
  smells (Bloaters, Object-Orientation Abusers, Change Preventers, Dispensables,
  Couplers) and apply named techniques (e.g. Extract Method/Function, Rename,
  Inline, Move, Replace Conditional with Polymorphism). Refactor in small steps and
  keep behaviour unchanged.

## Default Output
```md
# Implementation: <Feature / Fix>

## What & Where
- Brief intent and the files/areas to change.

## Code Changes
Provide the concrete code (functions, classes, edits).

## Validation & Error Handling
- Input checks and error behaviour added.

## Manual Verification
- How to run/observe it working.

## Quality Gate
- [ ] Code formatted by the project formatter.
- [ ] Linter passes with no new warnings.
- [ ] Builds / runs.
- [ ] Clean naming, small functions, no dead code.
- [ ] Relevant code smells addressed (if refactoring).

## Notes / Risks
- ...
```

## Stage Contract (V-enforcement)

Working code ≠ done. This stage turns the DESIGN into code — the design came *before*
you, and the proof comes *after* you:

1. **Top-down coverage:** the dispatch prompt delivers the UPSTREAM DEFINITIONS (the
   design's `DS-` ids, the architecture's `ARCH-` dependency rules) — read them before
   writing; cite the ids your change honors. Never work concrete detail without them.
2. **Your artifact:** the changed files/branch, **recorded with `task_add_artifact`**
   before `in-review` (a definition-stage exit without a recorded artifact is rejected).
3. **DONE = the four boxes:** artifact recorded · coverage declared (upstream ids cited) ·
   every acceptance criterion verified against the actual result · the quality gate ran.
   "It works" is **proven on the right leg — `test-software-implementation`** — never by
   this stage's own claim. You claim `in-review`; the gate/reviewer declares done.
4. **Flow back only on two triggers:** the design/architecture is *missing* something the
   code needs, or *needs refactoring/clarification* — `task_send_back` (or
   `clarification:` first). A failed test later loops back *horizontally* to this stage's
   pair (see `test-software-implementation`); that is the right leg's verdict, not yours.

## When to Hand Off
- **Up the chain:** unclear or missing design goes back to **software-design**.
- **Across:** code behaviour is verified by **test-software-implementation** (unit test).
