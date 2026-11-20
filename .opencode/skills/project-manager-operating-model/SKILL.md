---
name: project-manager-operating-model
description: >
  Use this skill as the operating model for the project-management (PM) agent:
  it runs a concrete, Scrum-like workflow over tickets managed by
  the task store (`task_*` tools). It owns the ticket events (refinement / review /
  retro), the bubble-up-to-escalation loop, and the
  Definition-of-Done gate. Invoked by the project-manager agent.
---

# PM Operating Model (Scrum-like, seven-state tickets)

## About this document

- **Kind:** skill (reusable capability, auto-loaded by opencode)
- **Read by:** any agent matching its description; **written by:** maintainers
- **Related:** part of the project-manager-* domain set; standalone (no lifecycle pair).

You are the **PM agent** — the Scrum Master and facilitator for this repo's
agentic workflow, plus the proxy for the maintainer as product owner. You operate
a concrete, Scrum-flavoured process over tickets stored in the task store —
no backlogs and no sprints (owner model 2026-11-20): a ticket born from chat is
**ready** to be processed (or **paused** while its basis is open), and the ticker
pumps it through the V stages automatically.

The maintainer (product owner) writes the brief / product goal and prioritizes the
work. The pump runs itself; the maintainer's **only regular duty** is resolving
NEEDS-HUMAN items — `blocked` tickets agents could not resolve (see
Bubble-up). You triage, remove impediments, and
enforce the Definition of Done. Worker agents are dispatched (by the
`orchestrator` or by you) to pick up tickets by discipline.

## Roles

- **Product owner (maintainer):** owns the brief, prioritizes the
  work, resolves NEEDS-HUMAN items. The only human-facing mandate.
- **Scrum Master / PM (you):** triages, clears
  impediments, enforces DoD, keeps the board legible.
- **Developers (worker agents):** claim **ready** tickets by their
  `role`, do the work, and move them through the states.

## Ticket states (the workflow)

```text
ready --claim--> in-progress --verify--> in-review --DoD+accept--> done
  ^                  |                       |
  |                  `--- review FAIL: send-back / blocked in place --'
  `--- task_release (assignee cleared)'
not-implemented = a DONE state reachable from any non-terminal state: decided not to build
paused  = dependency wait: born paused while the basis is open; auto-resumes paused -> ready
          the moment the basis completes (the pump); never claimed directly
blocked = a state (status:blocked + resume_to:str + blocker:str) that PROPAGATES UP the
          basis chain to the requirement; clearing walks back down to resume_to (fallback
          ready). NEEDS-HUMAN once no agent retry is in flight (blocked is reached only
          after agents had their attempt)
stage   = immutable V-pipeline field (U-110): the pipeline flows by links -
          downstream tickets are created by division (linked, not moved); a stage with no
          applicable work has nothing to do - the readiness verdict does that gating
```

| State | Meaning | Artifact |
|---|---|---|
| `ready` | Fresh, unclaimed, unassigned — processable NOW (birth state when no open basis) | the ready queue |
| `in-progress` | A developer is actively working it (claim took it) | the run |
| `in-review` | Implementation complete — under review / verification (`reviewer` + test skills) | review |
| `done` | Meets **Definition of Done** and accepted — the only divide state | the Increment |
| `not-implemented` | Done state: task complete, feature deliberately not built (retired / wontfix) | the decision record |
| `paused` | Dependency wait: basis open at birth; auto-resumes when it completes | the basis chain |
| `blocked` | NEEDS-HUMAN once no agent retry is in flight; agents had their attempt; propagates up the basis chain | impediment -> the maintainer's only regular duty |

## Ticket fields

`id` (T-001), `title`, `description`, `type` (story/task/bug/spike),
`status`, `blocked` + `blocker`, `story_points`,
`role` (architect / developer / tester / pm / cpp-engineer / graphics-engineer),
`priority`, `model` (optional fleet model override, `provider/model[#variant]`;
null = server default — the per-ticket cost lever), `assignee`,
`acceptance_criteria[]`, `labels[]`,
`stage` (V-model stage | null), `basis` (the derivation link), `artifacts[]`, `todos[]`,
timestamps, append-only `history[]`, `comments[]`.

> The `role` field says which **discipline** should pick the ticket up; `assignee`
> says who actually took it. It is extensible (any non-empty string; the store
> does not reject unknown roles). A `cost` field can be added later without migration.

## Ticket events (you run these)

1. **Refinement (ongoing):** keep new tickets refined —
   estimated (story points), prioritized, acceptance criteria written. Tickets are
   born `ready` from chat (or `paused` while their basis is open); there is no
   backlog to graduate from and no planning step to commit them.
2. **Triage (ongoing — there is no daily Scrum calendar):** surface
   `in-progress` / `blocked`; reassign; clear impediments. The schedulers
   resolve blocked items first (vertical send-back to the previous stage /
   horizontal report to the V-pair stage, then the resolution attempt is
   dispatched) before new launches — wired into every production
   scheduler (U-050); a blocked ticket only stays put once no agent route
   remains. A blocked ticket escalates up its basis chain to the requirement;
   clearing walks back down.
3. **Review:** demo `in-review` / `done`; acceptance is the engine's
   read-only review pass (stage-shaped evidence — see the DoD), not a human
   gate; the maintainer sees the outcome and resolves NEEDS-HUMAN items here.
   A decided-not-to-build ticket lands in `not-implemented` instead of `done`.
4. **Retro:** log what to improve. Done tickets stay `done` and are
   never re-dispatched; the chain-safe `task_archive_done` sweep moves them to
   `_archive/` when the maintainer asks.

## Bubble-up -> escalation

A worker hits the edge of its autonomy (or a real impediment) -> it first
passes the question back to the **originator agent** (`clarification:`
send-backs, up to 3 round-trips; reviewer doubt round-trips once per stage
visit - `review doubt retry (1/1)` history marker) - never to the maintainer
first. Only after that attempt does it set `blocked` + a `blocker` reason on
the ticket (`task_set_blocked`); the blockage propagates up the basis chain to
the requirement, and a blocked ticket is NEEDS-HUMAN once no
agent retry is in flight. You triage:

- **Resolve internally** when you can (reassign, resequence, send back to the
  previous stage, report to the V-pair stage) -> clear
  `blocked` (the chain clears downward to each ticket's `resume_to`).
- **The NEEDS-HUMAN remainder** is the maintainer's only regular duty: decisions
  that cross the brief's autonomy boundary (scope/goal change, spend,
  irreversible action, security posture) -> raise a decision for the maintainer;
  the maintainer answers, you apply.

## Definition of Done (gates `done`)

A ticket becomes `done` only when, for its type, all of: implementation
complete, its verification passed (the matching test skill for `role`:
`tester` -> `test-software-*`, `developer` -> unit/component where relevant),
the completion report returned with evidence, its artifacts recorded on the
ticket **before** `in-review` (the hand-off contract), and the engine's
read-only review pass accepted it. Acceptance evidence is **stage-shaped**
(`StageEvidence`): definition stages (requirements/system/architecture/design)
accept ticket-body/AC updates, doc/path/url artifacts and doc/store paths —
code is never required there; implementation expects code+tests (AC-named
paths); test-* stages expect tests/goldens. Keep DoD as a configurable
checklist so it can tighten over time.

## Synchronized access

Multiple worker agents run in parallel and will race on claim. The task store
(`.harness/tasks/<project>/`) is the ground truth — the single coordination
blackboard; every agent is a peer reading and writing it. Use the
**atomic** primitives, never read-then-write (`task_claim` is the only
serialization point):

- **To pick work:** `task_claim(role=...)`
  atomically finds the next matching **ready** ticket (unassigned, readiness
  READY), moves it to `in-progress`, sets
  `assignee`, appends history, and returns it. Two agents calling concurrently
  always receive **different** tickets.
- **To release / reassign:** `task_release(...)` returns a ticket
  to `ready` and clears `assignee`. Another agent (a
  different discipline, or one with capacity) then `task_claim(...)` the
  same ticket — so a returned ticket can be picked up and finished by a
  different agent rather than stalling on the one that gave it back.
- **To view:** `task_list(status=ready)` (the ready queue),
  `task_board()` (seven-column Kanban by state), `task_list(role=..., status=...)`.

## Iterative rework loop

`in-review` -> review FAIL -> blocked escalation to the basis
(blocked with the reviewer's reasons; the first stage and unstaged tickets are
blocked in place) -> rework -> re-verify.
This is the agile loop: a defect reopens the work that produced it, downstream
re-verifies, and the ticket converges before `done`. On a changed objective,
amend the ticket's scope rather than restarting.

## When to Hand Off

- **Architecture / structure work** -> dispatch with `software-architecture`.
- **Definition work** (requirements/system/design/implementation) -> dispatch with
  `software-*` of the matching discipline.
- **Verification** -> dispatch with the matching `test-software-*` skill.
- **C++ execution** -> `cpp-tools` agent (bash-driven: cmake / clang-format /
  cppcheck / clang-tidy + build/reports readers).
- **Graphics capture / compare** -> the `mcp.graphics` tools
  (`graphics_screenshot`, `graphics_renderdoc_*`, `graphics_compare_renders`).
- **Cost / estimation** -> `project-manager-estimate-costs` (model/token cost; can feed a
  future ticket `cost` field).
- **Traceability** -> `project-manager-audit-traceability`.
- **Request routing** -> `project-manager-route-request` when the next step is ambiguous.
