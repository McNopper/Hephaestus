# Requirements: the async stage pipeline (U-110)

- **About this document:** the requirements definition for U-110 — WHAT the
  harness must do and WHY when it runs as the async stage pipeline. Each
  requirement cites the U-111 acceptance criterion (AC-1..AC-7) it formalizes
  and the governing design section that already decides the HOW. This is the
  derivation basis for the system → architecture → design → implementation
  stages of this arc.
- **Read by:** the downstream stages deriving from U-111; the testers writing
  the acceptance tests; the maintainer.
- **Related:** `docs/design-stage-pipeline.md` (the governing design — the
  HOW; trace to it, do not duplicate), ticket
  `.harness/tasks/hephaestus/U-111.md`, `docs/opencode-v2-adoption.md`
  (W1/W12/W13 adoption policy), `docs/research-epic-decomposition-2026-11.md`
  (why epic and basis are separate relations).

## Goal

Make the Hephaestus harness run work as an async stage pipeline: stationary
tickets linked by a derivation graph, autonomous per-stage tickers spawning
goal-driven subagents under a spawn cap, division at completion, and an
engine-owned verdict — so the maintainer's only regular duty is resolving
escalations (NEEDS-HUMAN).

## Users

- **Product Owner** (the maintainer): states goals, resolves NEEDS-HUMAN.
- **Stage tickers** (one steward session per stage): run micro-sprint
  kickoffs over their stage's READY backlog.
- **Workers** (background subagents): finish exactly one ticket each.
- **Assistant** (chat session): the PO's proxy — intake, kickoff, surfacing;
  never works tickets, never self-accepts.

## User Stories

- US-001: As the PO, I want tickets to stay in one stage and flow by links, so that progress is visible without tracking a moving cursor.
- US-002: As the PO, I want the machinery to run autonomously under a spawn cap, so that my only regular duty is resolving escalations.
- US-003: As the PO, I want `done` decided by the engine's review pass, so that no agent grades its own work.

## Functional Requirements

### Store contract (AC-1)

- **FR-001 (AC-1)** ubiquitous: THE task store SHALL maintain a directional
  `basis` field on every ticket (child → the upstream ticket whose product it
  derives from), settable at creation, readable, and updatable via store APIs.
- **FR-002 (AC-1)** unwanted: IF a definition-stage ticket (`requirements`..
  `implementation`) reaches `done` with zero recorded artifacts, THEN the
  store SHALL reject the transition (guard enforced at the `done` transition,
  migrated from advance-time).
- **FR-003 (AC-1)** event-driven: WHEN an upstream ticket divides, the store
  SHALL create the downstream ticket(s) with `epic` inherited from the
  completing ticket, `basis` = the completing ticket, and the upstream's
  artifact references carried over (the chain carries content, not just IDs).
- **FR-004 (AC-1)** event-driven: WHEN a definition-stage ticket divides, the
  system SHALL also create its paired `test-*` ticket (same epic, basis = the
  ticket it verifies), gated by readiness on its basis reaching `done`.

### Readiness (AC-2)

- **FR-005 (AC-2)** ubiquitous: THE readiness evaluation SHALL derive
  upstream/downstream relations and READY/STALE state from basis chains.
- **FR-006 (AC-2)** state-driven: WHILE a ticket carries no basis, the
  readiness evaluation SHALL fall back to the legacy epic-chain semantics.
- **FR-007 (AC-2)** event-driven: WHEN a basis-upstream's `inputs_changed_at`
  stamp (U-052 semantics, landed first) changes after the downstream ran, the
  downstream SHALL be reported STALE.

### Retired movement verbs (AC-3)

- **FR-008 (AC-3)** ubiquitous: THE tool schema and store API SHALL NOT
  expose `task_advance`, `task_pass_stage`, `task_report_horizontal`, or
  send-back-as-movement.
- **FR-009 (AC-3)** event-driven: WHEN a downstream ticket blocks, the system
  SHALL escalate the blockage to its basis (a rework request on the upstream
  link; NEEDS-HUMAN if the basis is blocked or the round-trip repeats).
  `clarification:` routing stays — routing, not movement.

### Fleet retirement (AC-4)

- **FR-010 (AC-4)** ubiquitous: THE build reactor SHALL NOT include the
  `com.opencode.ide.fleet` or fleet.tests bundles.
- **FR-011 (AC-4)** ubiquitous: THE Board SHALL NOT reference fleet
  machinery — its fleet-touching files deleted or stripped,
  MANIFEST/plugin.xml/ui perspective cleaned.
- **FR-012 (AC-4)** ubiquitous: deploy scripts and docs SHALL reflect the
  fleet-free state.

### Ticker substrate (AC-5)

- **FR-013 (AC-5)** state-driven: WHILE the schedules flag is on, every stage
  SHALL run exactly one steward ticker session on a schedule (default 15
  min/stage, owner-adjustable; dormant when OFF).
- **FR-014 (AC-5)** event-driven: WHEN a tick fires, the ticker SHALL run the
  fixed lifecycle scan → blocked-escalate → done-divide → ready-kickoff →
  close, as a micro-sprint (kickoff/work/close) reusing one rolling per-stage
  record `ticker-<stage>` (reopened at kickoff, closed at the tick boundary).
- **FR-015 (AC-5)** ubiquitous: THE ticker SHALL enforce the global subagent
  spawn cap (default 4 concurrent) and serialize spawns whose file lanes
  overlap.
- **FR-016 (AC-5)** ubiquitous: THE common `stage-pipeline` skill SHALL
  encode the shared procedure parameterized per stage by division map +
  discipline skill; the tick prompt is thin glue over it.

### Goal-driven fulfillment (AC-5, AC-6)

- **FR-017 (AC-5)** event-driven: WHEN a ticker claims a ticket, it SHALL
  spawn exactly one background goal-driven subagent whose objective is
  "finish this ticket" (produce the stage product, record the artifact, set
  `in-review`); the subagent SHALL never accept and never wander.
- **FR-018 (AC-5)** ubiquitous: THE worker brief SHALL be narrow — its
  ticket, the direct basis artifacts, its stage skill + the common process
  skill, and its lane constraints; no store-wide visibility.
- **FR-019 (AC-6)** event-driven: WHEN the pipeline runs end-to-end, one
  requirements ticket SHALL flow to a linked, fulfilled downstream child
  through ticker + subagent (the acceptance run), and the evidence SHALL be
  recorded on U-111 with the engine verdict — never a self-assessment.

### Artifacts store and verdict (AC-1, AC-6)

- **FR-020 (AC-1)** optional-feature: WHERE the artifacts flag is on, stage
  products SHALL be published to the upstream artifacts store with the
  ticket's artifact ref pointing at the artifact view URL; WHERE it is off,
  repo-path refs SHALL be used unchanged.
- **FR-021 (AC-6)** ubiquitous: THE engine's review pass + quality gate SHALL
  be the only `done` authority; neither the worker nor the ticker grades
  itself.

### Docs and gate (AC-7)

- **FR-022 (AC-7)** ubiquitous: AGENTS.md (V-section + fleet sections),
  fleet-quickstart and the operating model SHALL be updated in the same arc
  so the documented process matches the implemented pipeline.
- **FR-023 (AC-7)** ubiquitous: THE quality gate SHALL be green on the final
  state (`build.ps1 clean verify -Pquality`).

## Non-Functional Requirements

- **NFR-001 Determinism:** the tick algorithm is identical across all ten
  stages; only per-stage data differs (division map, skill, artifacts).
- **NFR-002 Decoupling:** stages communicate only through the store; no
  direct handoffs; the tick boundary is the only synchronization point.
- **NFR-003 Autonomy:** the machinery runs unattended and keeps running when
  chat closes; NEEDS-HUMAN is the only human surface.
- **NFR-004 Cost observability:** native usage/token stats feed the cap
  decisions; no parallel bookkeeping.
- **NFR-005 Recoverability:** no worktree isolation (accepted risk, owner
  2026-11-14); recovery from a broken run is git revert.

## Constraints & Assumptions

- **C-001 Keep-list:** the plugin keeps chat, tasks, Board (stage columns),
  client, core. D-005 minimal core = store + engine verdict + spawn cap +
  Eclipse host.
- **C-002 Stationary ownership:** the ten canonical stages stay; `stage:` is
  immutable ownership set at creation (`null` legal), never a cursor.
- **C-003 Two relations:** `epic` = scope container (one epic groups its
  whole derivation tree; done = rollup), `basis` = derivation edge
  (direction, traceability). Milestones are never scope containers — no new
  construct.
- **C-004 Retirement ordering:** the fleet is removed LAST, only after live
  parity evidence (the acceptance run) validates the whole loop; wandering
  verbs and RecurringWaves go at chunk 5 after tickers hold parity.
- **C-005 Native-first:** adopt `experimental.schedules`, `experimental.
  artifacts`, background subagents, `experimental.job.list`; reimplement
  nothing these provide.
- **C-006 Backward compatibility:** store semantics (status machine, claims,
  blocked states, plan/claim/review) keep working as-is; traceability keeps
  its signals (the wandered-pairs-with-itself signal disappears with
  division-created pairing).

## Acceptance Evidence (engine-checked)

- **AE-1 (AC-1):** store tests prove basis create/read/update, the done-time
  guard rejecting a definition `done` without artifacts, and division
  producing linked + paired tickets.
- **AE-2 (AC-2):** readiness reports READY/STALE from basis chains, falls
  back for unbasis'd tickets, with the U-052 stamp landed first.
- **AE-3 (AC-3):** the tool schema/store API lists none of the wandering
  verbs.
- **AE-4 (AC-4):** the reactor builds with no fleet bundles; no fleet
  references remain in board code, MANIFEST, plugin.xml, deploy, docs.
- **AE-5 (AC-5):** live ticker evidence — `ticker-<stage>` records opened and
  closed per tick; the spawn cap observable on live runs.
- **AE-6 (AC-6):** the acceptance run recorded on U-111: requirements →
  linked fulfilled child, with the engine verdict attached.
- **AE-7 (AC-7):** `clean verify -Pquality` green; docs updated in the same
  commit arc.

## Open Questions

- Q-001: final ticker interval and cap values (design marks them OPEN —
  decided with chunk 3's bootstrap; defaults 15 min / 4 carried as the
  requirement baseline).
- Q-002: Board switch relabeling (Auto/Tickers) — a chunk-5 UI decision,
  does not change any requirement above.
