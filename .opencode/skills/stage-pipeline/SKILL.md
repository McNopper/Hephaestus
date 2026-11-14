---
name: stage-pipeline
description: Execute the linked stage pipeline as a stage agent - the abstract tick procedure and the per-ticket fulfillment loop. Load when running a stage ticker tick or fulfilling a stage ticket.
---

# Stage pipeline (U-110)

You are a stage agent in the linked stage pipeline. The procedure is ABSTRACT
and identical for every stage; only your stage's data differs (its division
map, its discipline skill, its artifacts). Tickets are STATIONARY: they are
born in a stage and never move - completion creates and links the downstream
tickets instead.

## The tick (ONE pass over the whole V - always the same)

Run this loop exactly. Do not improvise steps or order. ONE schedule
(`ticker-v`) walks the whole V: for each stage in canonical order —
requirements, system, architecture, design, implementation,
test-implementation, test-design, test-architecture, test-system,
test-requirements — run steps 1-4, then step 5 once.

Per stage:

1. SCAN — `task_readiness` for the project; take only this stage's tickets.
2. BLOCKED → ESCALATE BACK: a blocked ticket goes to its basis (the upstream
   link) as a rework request via `task_set_blocked` on the basis with the
   reason; if the basis is itself blocked, or the round-trip repeats, escalate
   to NEEDS-HUMAN (`blocked` + reason naming the human decision needed).
   Agents resolve what agents can resolve; the rest surfaces to the Product
   Owner.
3. DONE → PROCESS FORWARD (division): for each done ticket without downstream
   children, create the next-stage ticket(s) per your stage's division map —
   `task_create` with `stage` = the child stage, `epic` = the parent's epic
   (scope inherits), `basis` = the done ticket's id (the derivation edge), and
   carry the artifact references into the description. Pairing: also create
   the paired `test-*` ticket linked to the same basis when the child is a
   definition stage.
4. READY → KICKOFF: **plan first, then claim** — READY tickets born from
   division sit in `product-backlog` without a sprint, and `task_claim`
   only takes `sprint-backlog` tickets: `task_plan_sprint` them into the
   rolling `ticker-v` sprint record in the same tick, then claim under the
   spawn cap (`task_claim`), then spawn ONE
   background subagent per claimed ticket with the fulfillment brief
   (below). Never spawn two subagents whose file lanes overlap; when lanes
   overlap, serialize within the stage.
5. CLOSE the pass: reopen the `ticker-v` sprint record with the digest
   (U-108 reopen semantics keep the record rolling) and close it. The next
   tick repeats.

An empty backlog costs one readiness query. That is a successful tick.

## Fulfillment (worker subagents - one ticket, nothing else)

Your world is the ticket: its content, the DIRECT basis artifacts (from the
artifacts store or repo paths), your stage's discipline skill, and your lane
constraints. You have no big picture and need none — the ticket + the basis
product define done.

1. Read the ticket and its basis artifacts.
2. Do the work in your declared file lane only.
3. Record the stage product: `task_add_artifact` (kind doc/file with the
   product path — or publish to the artifacts store when enabled).
4. Set the ticket `in-review`. NEVER set `done`: the verdict is the engine's
   review pass + quality gate, never your self-assessment.
5. If blocked, `task_set_blocked` with the reason naming what you need. Do
   not improvise around a blocker.

## Boundaries (non-negotiable)

- The stage product is recorded BEFORE `done` — done-time enforcement refuses
  bare definition tickets; do not fight it.
- The spawn cap is a hard limit: at most N subagents running concurrently
  (N = the configured cap). Queue, do not exceed.
- Tickets stay in their stage. Fulfillment moves the LINKED downstream
  tickets, never the ticket itself.
- The user is the Product Owner; the main chat agent is the kickoff
  assistant; you are the worker. Escalate instead of guessing beyond the
  ticket.

## Field observations (live acceptance run, 2026-11-14)

- **Claims are targeted, not role-based.** A blind role-claim grabs the next
  sprint-backlog ticket of the role — which can cross into ANOTHER sprint's
  backlog. Pre-check the candidate's assignee/status, then claim with a
  targeted `task_update` (status + assignee in one move).
- **Division-with-rationale for legacy done tickets:** a done ticket whose
  derivation already exists in substance (delivered under the old model,
  `basis: null`) gets division recorded SATISFIED with a comment on the
  ticket - do not mint duplicate children for shipped work. The PO can
  override with one comment and the next tick divides literally.
- **Rolling records stay open while work is in flight:** `task_close_sprint`
  ejects unfinished tickets to product-backlog, corrupting in-flight
  fulfillment. Leave the record active until the work lands; a later tick
  closes it.
- **Nested steward sessions need `experimental.subagent_depth >= 2`** in the
  user-global config (setup-local per D-004) - the ticker itself is one
  subagent level, its workers the second.
- **Store access lost mid-flight** (gate rebuilds kill the stdio server,
  B-027): report BLOCKED honestly; do not improvise hand edits. Fresh
  sessions re-arm against the stable dropins jars (the launcher resolves
  gate-stable-first since the B-027 fix).
- **Pause/resume semantics (owner directive, days-run):** shutting the PC
  down pauses the pipeline - schedules are bound to sessions on the running
  service and die with it. Resume after boot = re-run
  `ticker-setup.ps1` with the same `-Stages`; the store is the memory,
  nothing else needs re-arming. A fresh `deploy-dev.ps1` also kills the
  live `tasks` server (jar replaced under it) - reconnect via
  `POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`.
