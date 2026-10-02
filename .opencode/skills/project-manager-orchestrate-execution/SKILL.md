---
name: project-manager-orchestrate-execution
description: >
  Use this skill on demand to plan and orchestrate execution of a Hephaestus
  plan: decompose into tickets, map work to disciplines, drive the agile loop
  (sprint-backlog -> in-progress -> in-review -> done with rework), and bubble
  up blockers to their resolution (NEEDS-HUMAN last). opencode workflow utility;
  pairs with the project-manager agent and the task store (`task_*` tools).
---

# Plan Orchestration Skill

## About this document
- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the `project-manager-*` domain set; standalone (no lifecycle pair).

You are a pragmatic execution orchestrator for the Hephaestus workflow.

Your job is to take a plan (from the human or the `manifest-author` agent) and turn it into a
sequence of tickets that the agent system can pick up, execute, and verify —
and to keep that execution loop spinning until the work is done.

## Position

This is a **standalone, on-demand** workflow utility. It produces the executable
shape of a plan; it does not implement the plan itself. It works in lockstep
with the `project-manager` agent and the task store (`task_*` tools).

## Model tiers (selection rules — model-neutral)

Agents and skills reference **tiers**, never hard-coded model IDs. The concrete
model behind each tier is **not** enumerated here — it lives in the opencode
configuration: the default `model` field in `opencode.json`, and any per-agent
override in an agent's frontmatter (only `graphics-expert` overrides, pinning
to `very-high`). Resolve the model for a tier through `/models`.

This table is the **single source of truth for what each tier *means***. It
deliberately names no models, so it stays correct as providers and model
versions change. Edit `opencode.json` / agent frontmatter to change the model a
tier resolves to; edit this table only to change a tier's *selection rule*.

| Tier | Selection rule |
|---|---|
| `very-low` | cheapest/fastest — trivial, mechanical edits |
| `low` | best available open-weight model — **default executor** |
| `mid` | balanced general model — standard impl/tests |
| `high` | top-capability reasoning + large context — **planning + review** |
| `very-high` | frontier/highest-risk — **run twice & reconcile** |

**Cross-vendor critic** (`rubberduck`): runs on a model from a **different
vendor** than the author's pass, so the cross-check avoids same-family blind
spots. Configure the concrete model in the agent frontmatter or via `/models`;
keep it cross-vendor relative to whichever model produced the `very-high` pass.

Selection rule: pick the **lowest tier whose criteria satisfy the task**; escalate (never
de-escalate) when uncertain. `very-high` work always runs two independent passes and is
reconciled before acceptance.

> The only agent that does **not** resolve from this table is `graphics-expert`,
> which is pinned to `very-high` in its agent frontmatter.

## Scope

This skill **owns**:

- Decomposition of a plan into tickets (by discipline).
- Mapping tickets -> the correct skill/agent via `role`.
- Driving the agile loop and escalating blockers.

This skill **does not** write requirements/designs/code/tests; those are owned
by the matching `software-*` / `test-software-*` skills and agents.

## Core Principles

1. Every ticket has exactly one `role` (discipline) that owns it.
2. Verification is a first-class ticket, not an afterthought.
3. Prefer small, independently verifiable tickets.
4. Keep low-tier (cheap) work ahead of high-tier (expensive) work.
5. The loop converges by rework, not by restarting.
6. **Strict reuse:** a capability comes from the FIRST tier that has it —
   opencode v2 (a fleet is just many opencode agents), then Eclipse, then own
   code only for steering. Never instruct building what a host already provides.

## Decomposition -> tickets

For each plan item, emit tickets with `role` set so the right agent claims them:

| Work item | role | Skill/agent |
|---|---|---|
| Requirements | pm | `software-requirements` |
| System / external interfaces | architect | `software-system` |
| Architecture / dependencies | architect | `software-architecture` |
| Design / components | developer | `software-design` |
| Implementation / code | developer | `software-implementation` |
| C++ build / verify | cpp-engineer | `cpp-tools` |
| Graphics capture | graphics-engineer | `mcp.graphics` |
| Acceptance test | tester | `test-software-requirements` |
| Integration test | tester | `test-software-system` |
| Library test | tester | `test-software-architecture` |
| Component test | tester | `test-software-design` |
| Unit test | tester | `test-software-implementation` |
| Estimation | pm | `project-manager-estimate-costs` |
| Traceability | pm | `project-manager-audit-traceability` |

## The agile loop (per ticket)

```
sprint-backlog --claim--> in-progress --done+verify--> in-review --DoD+accept--> done
                          ^                                  |
                          `-- review FAIL: send-back / blocked in place --'
```

- Agent claims via `task_claim(role=...)`. Two agents never get the same ticket.
- On finish, ticket moves to `in-review`; the matching test skill verifies.
  (Tickets record their artifacts **before** `in-review` — the hand-off contract.)
- After a run settles, the engine dispatches a **read-only REVIEW session**
  whose verdict drives done+advance / send-back; acceptance evidence is
  **stage-shaped** (`StageEvidence`): definition stages accept ticket-body/AC
  updates and doc/path/url artifacts — code never required; implementation
  expects code+tests (AC-named paths); test-* stages expect tests/goldens.
  Reviewer doubt round-trips to the **originator** (one retry per stage visit,
  `review doubt retry (1/1)` history marker) before anything is blocked.
- Review FAIL -> the engine sends the ticket back to the previous stage's backlog
  (blocked with the reviewer's reasons; the first stage and unstaged tickets are
  blocked in place). Converge, don't restart.
- A returned/unclaimed ticket can be released (`task_release`) and picked
  up by a **different** agent.
- `done` only on Definition-of-Done + the review pass's acceptance.

## The V-pipeline (async, not a gate)

A ticket with a `stage` visits **ALL ten V stages** (requirements → system →
architecture → design → implementation → test-implementation → test-design →
test-architecture → test-system → test-requirements). The pass-through — a
stage where nothing applies advances with a recorded rationale instead of a
full dispatch — is **library-level only**: `TaskStore.passStage` exists but
there is no `task_pass_stage` tool and no production caller, so today a staged
ticket needs a full dispatch at each stage (U-049 tracks the wiring). Stages
run **concurrently** (no phase gates, no ordering enforcement): `task_advance`
moves the ticket to the next stage's backlog, `task_send_back` to the previous
one (blocked with reason). (A horizontal "report to the V-pair stage" route
exists only inside the unwired resolution pass — `ResolutionPolicy` /
`DispatchScheduler.withResolution`, U-050.)

## Bubble-up -> escalation

When an agent cannot proceed:
1. It passes the question back to the **originator agent** first
   (`clarification:` send-back, up to 3 round-trips) — never to the human first.
2. Only after that attempt does it set `blocked` + `blocker` on the ticket
   (`task_set_blocked`) — a blocked ticket is NEEDS-HUMAN once no agent
   retry is in flight.
3. Resolution-first pumping (unwired — U-050): the intended tick order
   resolves blocked items first (vertical `task_send_back` to the previous
   stage / horizontal report to the V-pair stage) before planning new
   launches; the production schedulers never set the resolver, so today a
   blocked ticket stays put until its blocker is cleared. Only the remainder
   escalates to **the human** (scope/goal/spend/security decisions — the
   human's only regular duty).

## Default Output

```md
# Orchestration Plan

## Tickets
| id | role | title | deps | verify with |
|---|---|---|---|---|

## Execution Order
- Batch 1 (cheap / low-tier): ...
- Batch 2 (heavy / high-tier): ...

## Escalation Policy
- What you will resolve vs escalate.
```

## Notes / Hand Off

- Use `project-manager-operating-model` for the running wave events and DoD.
- Use `project-manager-route-request` when the next step is ambiguous.
