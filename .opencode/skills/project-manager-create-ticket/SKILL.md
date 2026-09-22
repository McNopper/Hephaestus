---
name: project-manager-create-ticket
description: >
  Use this skill as the template/guide for writing a correct Hephaestus ticket:
  choosing the right role, writing concrete acceptance criteria, sizing story
  points, and linking epics for traceability. Invoked by the project-manager agent, the
  manifest-author, or any worker that opens a ticket via task_create.
---

# How to Fill Out a Ticket

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the project-manager-* domain set; standalone (no lifecycle pair).

You are the **ticket authoring guide** for Hephaestus. A good ticket is
self-contained, verifiable, and traceable. Use it whenever you create a ticket
through `task_create` (task store).

## Why this exists

Tickets are the unit of work the whole fleet moves through. A fuzzy ticket stalls
the loop: a worker can't claim it, a reviewer can't accept it, and traceability
breaks. This skill keeps every ticket claimable and verifiable.

## Field-by-field guide

| Field | How to fill it |
|---|---|
| `title` | One line, active verb, states the outcome (e.g. "Add Vulkan swapchain resize"). |
| `description` | The what + why; enough context that a worker needs no further lookup. |
| `type` | `story` (user value), `task` (internal), `bug`, `spike` (research). |
| `role` | The **discipline that owns it** — see the role map below. This decides who claims it. |
| `priority` | `low`/`medium`/`high`/`critical`; drives claim order within a sprint. |
| `story_points` | Relative size (Fibonacci-ish: 1/2/3/5/8/13). Estimate, don't overthink; later `project-manager-estimate-costs` can feed a `cost` field. |
| `model` | Optional fleet model override (`provider/model[#variant]`). The cost lever of decomposition: a small, well-specified ticket can run on a cheap model — pin one when the work is mechanical; leave null (server default) when it needs the strong default. |
| `acceptance_criteria` | Concrete, checkable bullets ("Given … When … Then …"). Verification passes only when all are met. |
| `labels` | Free tags for filtering (e.g. `vulkan`, `regression`). |
| `epic` | Parent ticket id for traceability (definition ticket id on a verification ticket, or vice-versa). Optional but strongly recommended for linking. |
| `sprint` | Leave null — the PM commits it at Sprint Planning (`task_plan_sprint`). |

## Role map (who owns / claims the ticket)

| role | owns | claims via | verifies via |
|---|---|---|---|
| `architect` | system / architecture structure | `software-system`, `software-architecture` | `test-software-architecture`, `test-software-system` |
| `developer` | design / implementation | `software-design`, `software-implementation` | matching `test-software-*` |
| `tester` | verification tickets | `test-software-*` | (itself) |
| `pm` | requirements / estimation / traceability / ops | `software-requirements`, `project-manager-estimate-costs`, `project-manager-audit-traceability`, `project-manager-orchestrate-execution` | — |
| `cpp-engineer` | C++ build / format / static analysis | `cpp-tools` agent | `cpp-tools` agent |
| `graphics-engineer` | render capture / compare | `mcp.graphics` tools (+ `graphics-expert`) | `graphics-render-comparison` |

The `role` is **extensible** — it is an open, non-empty string, so you can mint a
new discipline on the spot (the task store accepts any non-empty value; it does
not reject unknown roles). The trade-off: keep the claim routing in
`project-manager-orchestrate-execution` (the role → skill/agent map) in sync whenever you
introduce one, or a worker claiming by that role won't find its skill. Prefer a
role from the known set above when one fits.

## Traceability by convention

- A verification ticket sets `epic` = its definition ticket's id (`FR-001`,
  `SW-002`, `ARCH-003`, …). The `project-manager-audit-traceability` matrix then shows the link.
- Keep stable prefixes per artifact kind: `FR-` requirements, `AC-` acceptance,
  `SW-` system, `ARCH-` architecture, `DS-` design, `UT-` unit, `CT-` component,
  `LT-` library, `IT-` integration, `T-` generic. Pass `id_prefix` to
  `task_create` to mint the right id.

## Definition of Done (matches `project-manager-operating-model`)

A ticket becomes `done` only when: implementation complete, its verification passed
(the matching `test-software-*` for `tester`, relevant tests for `developer`),
a completion report with evidence returned, and the human (PO) accepted at Review.

## Anti-patterns

- Don't open a ticket with no `acceptance_criteria` — it can't be verified.
- Don't mix roles in one ticket — split it so exactly one `role` owns it.
- Don't pre-assign `sprint` — let the PM plan it.
- Don't write "fix stuff" — name the artifact and the outcome.

## Recording artifacts (the hand-off contract)

The ticket is the hand-off contract: **whenever you produce something, point the
next agent to it.** Use `task_add_artifact` with a `kind` so the locator is
unambiguous:

| kind | ref example | when |
|---|---|---|
| `file` | `src/renderer/swapchain.cpp` | the file(s) you created/edited |
| `path` | `build/reports/cppcheck.txt` | a directory or report path |
| `git` | `abc1234` or `branch: feat/swapchain` | the commit or branch holding the work |
| `url` | `https://…/render-diff.png` | a remote resource (diff image, CI run) |
| `doc` | `ARCH-003` | another artifact/ticket id it depends on |

A worker must record its artifact **before** moving the ticket to `in-review`, so the
reviewer / next agent can find the actual output without asking. Keep the `note` short
("implemented module", "review branch", "diff image").
