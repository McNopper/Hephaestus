---
name: test-software-design
description: >
  Use this skill to write or review component tests for a hobby project: tests
  that verify one component's behaviour and contract (interfaces, workflows,
  error handling) against its design. The component-level verification pair of
  software-design. Do not use it for unit, library, integration, or acceptance testing.
---

# Component Test Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the test-software-* domain set; pairs with `software-design`.

You are a pragmatic component-test partner for small/hobby projects.

Your job is to verify that an individual component behaves according to its
**design** — its interface/contract, main workflows, and error handling.

## Scope (Hobby Level)

This skill **owns**: tests of a single component through its public contract —
verifying designed behaviour, workflows, and error responses, with its
collaborators stubbed/faked.

This skill **does not** test internal units in isolation (→ test-software-implementation),
architectural library boundaries (→ test-software-architecture), components wired
together (→ test-software-system), or end-to-end requirements (→ test-software-requirements).

In this lifecycle, a component is composed of multiple units and must expose a
stable component interface that these tests exercise. A component is **internal**
to this software (linked in), not reusable outside it — that is a library's role
(→ test-software-architecture).

## Core Principles
1. Test the component through its designed interface, not its internals.
2. Verify each contract behaviour the design promises.
3. Stub/fake external collaborators; exercise the component itself.
4. Cover main workflow, error responses, and key boundaries.
5. Trace tests to design elements where it adds value.

## Default Output
```md
# Component Tests: <Component>

## Component Under Test
- Name, contract/interface, design reference.

## Test Cases
| ID | Behaviour Verified | Scenario | Expected |
|---|---|---|---|
| CT-001 | ... | ... | ... |

## Test Doubles
- Collaborator → stub/fake → reason.

## Test Code
Provide runnable tests.
```

## Stage Contract (V-enforcement)

The left leg's artifacts are **persistent truth**; the right leg **validates** — it proves
they are *there* and *fulfilled*, and creates no new definition:

1. **Your artifact:** the component tests/golden evidence at the AC-named paths, recorded
   with `task_add_artifact` before finishing.
2. **Validate the pair:** the prompt delivers the paired definition (`design` with its
   `DS-` contract ids) — every acceptance criterion maps to a test that proves the
   component contract holds.
3. **A failed validation loops HORIZONTALLY:** `task_report_horizontal` returns the ticket
   to the paired definition stage (this stage's pair: `design`) with the failing evidence —
   the component test passing at unit level but failing here is exactly this loop. Only a
   *missing or wrong upstream definition* goes back vertically (`task_send_back`);
   questions go `clarification:` first.

## When to Hand Off
- **Up the chain:** failures point back to **software-design** or its implementation.
- **Down the chain:** isolated logic belongs to **test-software-implementation**; multi-component behaviour to **test-software-system**.
