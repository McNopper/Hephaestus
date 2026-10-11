---
name: test-software-system
description: >
  Use this skill to write or review integration tests for a hobby project: tests
  that verify the system's parts work together — libraries, services, APIs, storage,
  and external interfaces. The system-level verification pair of software-system.
  Do not use it for unit, component, library, or acceptance testing.
---

# Integration Test Skill

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the test-software-* domain set; pairs with `software-system`.

You are a pragmatic integration-test partner for small/hobby projects.

Your job is to verify that the **system's parts work together** — that libraries,
storage, APIs, and external interfaces from the system design connect correctly.

## Scope (Hobby Level)

This skill **owns**: tests that exercise two or more real parts together —
e.g. app + database, app + an external API — checking data crosses interfaces
correctly and key end-to-end-ish paths work.

This skill **does not** test isolated units (→ test-software-implementation),
a single component's contract (→ test-software-design), architectural rules
(→ test-software-architecture), or business acceptance from the user's view
(→ test-software-requirements). Pick the few integrations that carry real risk.

Integrations should be defined by real interfaces/contracts between components,
libraries, and externals — not by package/folder adjacency.

## Core Principles

1. Test real interactions across interfaces, not mocked-out ones.
2. Focus on the parts and data flows that carry the most risk.
3. Use realistic but disposable test data and environments.
4. Keep setup/teardown reliable so tests stay deterministic.
5. Cover the main success path plus key failure-at-the-boundary cases.

## Default Output

```md
# Integration Tests: <Interaction>

## Parts Under Test
- Which parts/interfaces are exercised together.

## Test Cases
| ID | Flow | Scenario | Expected |
|---|---|---|---|
| IT-001 | App ↔ DB | ... | ... |

## Environment / Data
- How the parts are stood up and seeded.

## Test Code
Provide runnable tests.
```

## Stage Contract (V-enforcement)

The left leg's artifacts are **persistent truth**; the right leg **validates** — it proves
they are *there* and *fulfilled*, and creates no new definition:

1. **Your artifact:** the integration tests/golden evidence at the AC-named paths,
   recorded with `task_add_artifact` before finishing.
2. **Validate the pair:** the prompt delivers the paired definition (`system` with its
   `SW-` interface ids) — every acceptance criterion maps to a test that proves the
   parts integrate as the system record says.
3. **A failed validation loops HORIZONTALLY:** `task_report_horizontal` returns the ticket
   to the paired definition stage (this stage's pair: `system`) with the failing evidence.
   Only a *missing or wrong upstream definition* goes back vertically (`task_send_back`);
   questions go `clarification:` first.

## When to Hand Off

- **Up the chain:** failures point back to **software-system**.
- **Adjacent chains:** single-component behaviour → **test-software-design**; user-facing outcomes → **test-software-requirements**.
