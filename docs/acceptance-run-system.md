# System: the acceptance run of the async stage pipeline (U-111)

- **About this document:** the system-level definition OF the acceptance run —
  the live end-to-end validation of the U-110 async stage pipeline. It fixes
  the run's major parts, each part's responsibility, the interfaces between
  the parts, and the end-to-end data flows that produce each demonstration
  point's evidence (D-1 stationary fulfillment, D-2 division + linking, D-3
  engine verdict). **Read by:** the stage tickers, the goal-driven workers,
  the engine review pass, the maintainer. **Related:**
  `docs/acceptance-stage-pipeline.md` (upstream: WHAT the run must prove — the
  demonstration contract, evidence list and pass/fail criteria this system
  satisfies), `docs/requirements-stage-pipeline.md` (the arc's FR/NFR
  baseline), `docs/design-stage-pipeline.md` (downstream: the HOW — model §1,
  tick §4, fulfillment §5, verdict §8).
- **Amendment 2026-11-15 (T-097):** the actor-attribution promise
  re-specified to the store's observable reality — status-transition events
  carry no stamped actor, so engine attribution rests on the review pass's
  observable marks (the annotated done event or the engine-verdict comment)
  plus `in-review` → `done` ordering, with a legacy rule for pre-mitigation
  dones. Escalated by paired verification A-002; see §Interfaces 1 and the
  D-point map.

## Overview

The acceptance run is not a program — it is the **task store plus four actors
around it**: stationary tickets, per-stage tickers, goal-driven workers, and
the engine review pass + quality gate as the only `done` authority. Stages
are decoupled through the store (no direct handoffs); the tick boundary is
the only synchronization point; every D-point is decidable from store
evidence alone — history events, the review pass's verdict comments,
ticket fields, recorded artifacts.

## Major Parts

- **Task store** (`.harness/tasks/hephaestus/`, one Markdown file per
  ticket) — the single coordination blackboard: tickets with immutable
  `stage:` ownership, `epic` (scope) / `basis` (derivation edge) links,
  artifact indexes, claims, sprint records, readiness, and the append-only
  history that carries the evidence. Mutated only through the `task_*` verbs;
  no hand edits.
- **Per-stage ticker** (one steward session per stage, `experimental.automations`)
  — runs the micro-sprint over its stage's READY backlog: SCAN readiness →
  BLOCKED escalate → DONE divide → READY kickoff → CLOSE + digest. Holds the
  big picture; never does the discipline work; never accepts.
- **Goal-driven worker subagents** (native background subagents) — one per
  claimed ticket, objective exactly *finish this ticket*: produce the stage
  product, record it as an artifact, set `in-review`. Narrow brief (ticket +
  direct basis artifacts + stage skill + common `stage-pipeline` skill + lane
  constraints); never accepts, never wanders.
- **Engine review pass + quality gate** — reviews each `in-review` ticket and
  runs the central gate (build/tests/lint) on the shared tree; the ONLY `done`
  authority. Neither worker nor ticker grades itself.

## Interfaces

- **`task_*` store verbs** — the only write path into the store. Tickers use
  `task_readiness`, `task_plan_sprint`, `task_claim`, the division/link
  creation, `task_close_sprint`. Workers use `task_get`, `task_add_artifact`,
  `task_update` (→ `in-review`), `task_add_comment`, `task_set_blocked`. The
  engine review uses `task_get` / `task_traceability` and performs the `done`
  transition. Every verb writes an append-only history event, and actor
  attribution on those events is partial: claim and artifact writes carry the
  acting identity; status transitions record only the change
  (`updated:status`, no stamped actor) — identities are caller-asserted peer
  conventions, not authenticated. Engine attribution therefore rests on the
  observable marks the review pass leaves on the ticket, read together with
  the `in-review` → `done` ordering: **(1)** an engine-annotated done event
  (observed 2026-11-14: `done (engine review pass: …)` by `review`), or
  **(2)** an engine-verdict comment in the ticket's own `comments[]` by a
  review-pass identity (observed: `review`, `ticker-v:review`; recorded on
  every PASS flip since 2026-11-15), landing within the review batch around
  the flip (observed span: 51 ms before to ~17 min after it). A `done` on or
  after 2026-11-15 with neither mark is not decidable as engine-set and fails
  D-3; `done`s before that date without a mark are grandfathered
  (pre-mitigation legacy). This is what makes each D-point decidable from the
  store.
- **Subagent spawn mechanism** — ticker → one background subagent per claimed
  ticket, under the global spawn cap (default 4), serialized when file lanes
  overlap. The spawn prompt IS the worker's narrow brief. The worker reports
  on idle.
- **The store files as the blackboard** — stages never call each other:
  division creates the downstream ticket(s) with `basis`/`epic` and the
  upstream's artifact references carried over, and the downstream ticker
  discovers them at its next tick via readiness (WAIT_UPSTREAM → READY). The
  ticket file IS the hand-off contract.

## Technology choices

| Concern | Choice | Why |
|---|---|---|
| Coordination store | Markdown ticket files + `task_*` MCP verbs | parseable, peer-writable, review-decidable |
| Scheduling | opencode `experimental.automations` (W1; renamed from schedules in 2.3.1) | native per-stage tickers, no new machinery |
| Workers | native background subagents (2.3.0) | goal-driven, idle-reported; the cap is the only admission knob |
| Artifacts | upstream artifacts store (W12) or repo-path refs (fallback) | the chain carries content, not just IDs |
| Verdict | engine review pass + quality gate | no self-grading |

## End-to-end data flows (per D-point)

- **D-1 Stationary fulfillment** (this run's instance: T-031): ticker tick →
  readiness marks the ticket READY → `task_claim` (history: claim, status →
  in-progress) → the ticker spawns one worker with the brief → the worker
  produces the stage product → `task_add_artifact` (history: artifact add by
  worker) → `task_update` to `in-review` (history: status transition; the
  worker's identity evidenced by its fulfillment comment adjacent to the
  flip). `stage:` is never rewritten; no `done` by worker or ticker.
  Evidence: the claim → artifact → `in-review` history chain — claim and
  artifact events actor-stamped, the status flips bare with the acting
  identities in the adjacent comments — on a ticket whose `stage:` never
  moved.
- **D-2 Division + linking** (this run's instance: T-031 → A-001): the engine
  sets the basis `done` → the next tick of the downstream stage's ticker (its
  DONE step) creates the child with `epic` = upstream epic, `basis` = the
  upstream id, artifact references carried over → `task_readiness` flips the
  child WAIT_UPSTREAM → READY once the basis is done → the child is claimed
  and fulfilled as in D-1. Evidence: `task_get` of the child (`basis`/`epic`
  set, artifacts carried); readiness before/after the basis `done`;
  `task_traceability` basis-pairing rows.
- **D-3 Engine verdict** (this run's instance: A-001): the worker leaves the
  ticket at `in-review` → the engine review pass reads ticket + artifacts and
  runs the quality gate → only the engine performs the `done` transition.
  Evidence: history carries `in-review` then `done`, and the done carries the
  engine's mark — an engine-annotated done event or the engine-verdict
  comment on the ticket within the review batch around the flip (legacy
  `done`s before 2026-11-15 without a mark are grandfathered, §Interfaces 1);
  no worker/ticker `done` event anywhere in the chain.

## D-point → parts / evidence map

| D-point | Parts that generate its evidence | Store evidence |
|---|---|---|
| D-1 | ticker (claim + spawn), worker (artifact + `in-review`), store history | claim → artifact events with actors → bare `in-review` flip + the worker's fulfillment comment; `stage:` immutable |
| D-2 | store (division + links + readiness), ticker DONE step | child's `basis`/`epic` + carried artifacts; WAIT_UPSTREAM → READY |
| D-3 | engine review pass + quality gate (sole `done` authority), store history + verdict comments | `in-review` → `done` ordering; engine mark on the done (annotated done event, or engine-verdict comment by `review` / `ticker-v:review` adjacent to the flip; pre-2026-11-15 dones grandfathered); no other `done` |

## Key trade-offs / open questions

- **No worktree isolation** (accepted risk, owner 2026-11-14): workers edit
  the shared tree; recovery from a broken run is git revert; the gate + commit
  close the loop.
- **Pass/fail bar:** PASS iff D-1, D-2 and D-3 each hold on store evidence
  alone; a worker-set `done`, a rewritten `stage:`, or a child never READY
  without manual intervention fails the run.
