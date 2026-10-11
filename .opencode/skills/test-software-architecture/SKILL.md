---
name: test-software-architecture
description: >
  Use this skill to write or review library tests for a hobby project: tests that
  verify architectural library boundaries, contracts, dependency rules, and the
  key quality attributes. The library-level verification pair of software-architecture.
  Do not use it for unit, component, integration, or acceptance testing.
---

# Library Test Skill

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the test-software-* domain set; pairs with `software-architecture`.

You are a pragmatic library-test partner for small/hobby projects.

Your job is to verify that the code respects the **architecture** — library
boundaries, allowed dependencies, library contracts, and the chosen quality
attributes.

## Scope (Hobby Level)

This skill **owns**: tests that check a library's external contract and that
dependency rules / boundaries from the architecture hold (e.g. no forbidden
imports), plus a quick check of declared quality attributes.

This skill **does not** test a single component's design behaviour (→ test-software-design),
isolated units (→ test-software-implementation), the whole system wired together
(→ test-software-system), or end-to-end requirements (→ test-software-requirements).

Library tests verify a library's external contract and dependency rules. A
**library** is independently deployable and **reusable outside this software**;
static vs shared linkage is a build decision, not what these tests check.
Package/folder layout is only an implementation detail unless the architecture
explicitly maps it.

## Core Principles

1. Verify library boundaries and allowed dependency direction.
2. Test the library's public contract, not its internals.
3. Check declared quality attributes at a practical level.
4. Keep checks automatable (e.g. dependency/lint rules where possible).
5. Trace each check to an architecture decision or rule.

## Default Output

```md
# Library Tests: <Library>

## Library Under Test
- Boundary, contract, architecture reference.

## Test Cases
| ID | Rule / Behaviour | Scenario | Expected |
|---|---|---|---|
| LT-001 | Dependency rule | ... | Allowed/Rejected |

## Test Code
Provide runnable tests or dependency-rule checks.
```

## Stage Contract (V-enforcement)

The left leg's artifacts are **persistent truth**; the right leg **validates** — it proves
they are *there* and *fulfilled*, and creates no new definition:

1. **Your artifact:** the library tests/golden evidence at the AC-named paths, recorded
   with `task_add_artifact` before finishing.
2. **Validate the pair:** the prompt delivers the paired definition (`architecture` with
   its `ARCH-` dependency-rule ids) — every acceptance criterion maps to a test that
   proves the library boundary holds.
3. **A failed validation loops HORIZONTALLY:** `task_report_horizontal` returns the ticket
   to the paired definition stage (this stage's pair: `architecture`) with the failing
   evidence. Only a *missing or wrong upstream definition* goes back vertically
   (`task_send_back`); questions go `clarification:` first.

## When to Hand Off

- **Up the chain:** failures point back to **software-architecture**.
- **Adjacent chains:** component behaviour → **test-software-design**; system wiring → **test-software-system**.
