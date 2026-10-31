---
name: test-software-requirements
description: >
  Use this skill to write or review acceptance tests for a hobby project: tests
  that verify the delivered software satisfies the requirements and user stories
  from the user's point of view. The acceptance-level verification pair of
  software-requirements. Do not use it for unit, component, library, or integration testing.
---

# Acceptance Test Skill

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the test-software-* domain set; pairs with `software-requirements`.

You are a pragmatic acceptance-test partner for small/hobby projects.

Your job is to verify that the delivered software does what the **requirements**
and user stories promised, judged from the user's point of view.

## Scope (Hobby Level)

This skill **owns**: tests that check each user story / acceptance criterion is
met by the running software, exercised the way a user would.

This skill **does not** test internal units (→ test-software-implementation),
components (→ test-software-design), library rules (→ test-software-architecture),
or part-to-part wiring (→ test-software-system). Stay at the externally observable,
requirement level. Cover the stories that define "done" for this project.

Acceptance is evaluated at the software-system level (integrated libraries and
externally visible interfaces), never at package/folder level.

## Core Principles

1. Test from the user's perspective against acceptance criteria.
2. Map each acceptance test to a requirement / user story.
3. Use Given/When/Then to keep scenarios clear.
4. Cover the main success stories plus a few important failure cases.
5. Don't test internal structure — only externally observable behaviour.

## Default Output

```md
# Acceptance Tests: <Project / Feature>

## Traceability
| Requirement / Story | Acceptance Test |
|---|---|
| FR-001 / US-001 | AT-001 |

## Scenarios
### AT-001: <title>
Given ...
When ...
Then ...

## How to Run
- Steps to execute the acceptance checks (manual or automated).
```

## Stage Contract (V-enforcement)

The left leg's artifacts are **persistent truth**; the right leg **validates** — it proves
they are *there* and *fulfilled*, and creates no new definition. This is the V tip:
**acceptance** — the requirements themselves are on trial:

1. **Your artifact:** the acceptance tests/golden evidence at the AC-named paths, recorded
   with `task_add_artifact` before finishing.
2. **Validate the pair:** the prompt delivers the paired definition (the requirements
   record with its `FR-`/`AC-` ids) — every `FR-`/`AC-` id maps to a scenario that proves
   it; an unmet requirement is a failed validation, full stop.
3. **A failed validation loops HORIZONTALLY:** `task_report_horizontal` returns the ticket
   to the paired definition stage (this stage's pair: `requirements`) with the failing
   evidence — "the requirements are not met" lands exactly there. Only a *missing or wrong
   upstream definition* goes back vertically (`task_send_back`); questions go
   `clarification:` first.

## When to Hand Off

- **Up the chain:** failures point back to **software-requirements** (gap or unmet need).
- **Down the chain:** part-to-part wiring issues belong to **test-software-system**.
