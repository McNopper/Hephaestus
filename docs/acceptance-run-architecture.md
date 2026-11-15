# Architecture: the acceptance run of the async stage pipeline (U-111)

- **About this document:** the architecture-level definition OF the
  acceptance run — the internal structure of the system fixed by its basis
  `docs/acceptance-run-system.md` (ticket A-001, done 2026-11-14 by engine
  review verdict). It assigns every system part to exactly one primary
  architectural module, fixes the dependency rules between modules as
  **default-deny edges**, names the interfaces where the modules meet this
  repo's real code (the task store, the `stage-pipeline` skill, the native
  schedules/subagent machinery), and traces every demonstration point —
  D-1 stationary fulfillment, D-2 division + linking, D-3 engine verdict —
  through the modules to its store evidence. **Read by:** the stage tickers,
  the goal-driven workers, the engine review pass, the paired
  test-architecture ticket (A-004), the maintainer. **Related:**
  `docs/acceptance-run-system.md` (the upstream basis — the parts,
  interfaces and data flows this architecture structures; cited as [SYS]),
  `docs/acceptance-stage-pipeline.md` (the run's requirements — the
  demonstration contract, evidence list, pass/fail bar; cited as [REQ]),
  `docs/requirements-stage-pipeline.md` (the FR/NFR baseline; cited as
  FR-xxx / NFR-xxx), `docs/design-stage-pipeline.md` (the U-110 design —
  where it already fixes structure this doc records the mapping instead of
  new structure; cited as [DSG]); tickets A-003 (this stage) and A-004
  (the paired verification of this doc).

## Scope

The acceptance run is not a program — it is a **process/agent architecture**:
its "modules" are modules of the run, each anchored to a real surface of
this repo (store directory, skill files, schedule config, subagent spawn),
not to a package or library lane [SYS §Overview]. The architecture question
this doc answers is therefore: *which module owns which responsibility, who
may write to whom through which interface, and through which module chain
each D-point's evidence flows* — so that a reviewer reading only the store
can decide the run [REQ §Pass/fail criteria]. Everything structural that
[DSG] already decides is recorded as a mapping (§Pass-through), not
re-decided.

## Chosen pattern

Evaluated against the system definition: a **layered** pattern alone would
imply call stacks between tiers that the run forbids (stages never call each
other [SYS §Interfaces 3; DSG §4]); **client-server** captures only the
verb interface and misses the peer/actor structure; **event-driven** would
wrongly license reactive links — the run has no bus; "events" are store
history read at the tick boundary, the only synchronization point [SYS
§Overview; DSG §4].

Chosen: **blackboard over the task store, driven by a fixed control
cadence.** One shared data substrate (the tickets, mutated only through the
`task_*` verbs [SYS §Major Parts 1]) with independent actors around it —
exactly the system definition's "the task store plus four actors around it"
[SYS §Overview] — and one control loop per stage whose procedure is fixed
[DSG §4]. This is the pattern the basis describes; the architecture's job is
to name its modules and make its edges explicit and default-deny.

## Modules and layers

| Module | Layer | Responsibility (from the basis) | Anchored in (real code) | Basis |
|---|---|---|---|---|
| **M1 `store`** | substrate | The single coordination blackboard: tickets with immutable `stage:` ownership, `epic` (scope) / `basis` (derivation edge) links, artifact indexes, claims, sprint records, readiness, and the append-only history that carries the evidence. Mutated only through the `task_*` verbs. | `.opencode/tasks/hephaestus/` (one Markdown file per ticket, `_meta.json`) + the `task_*` MCP verb surface | [SYS §Major Parts 1; §Interfaces 1; FR-001..FR-007] |
| **M2 `ticker`** | control | Per stage: runs the fixed tick — SCAN readiness → BLOCKED escalate → DONE divide → READY kickoff → CLOSE + digest — as a micro-sprint over the rolling per-stage record `ticker-<stage>`. Holds the big picture; never does the discipline work; never accepts. | One steward session per stage on `experimental.schedules` (W1); armed/disarmed by `.opencode/skills/stage-pipeline/ticker-setup.ps1`; the tick prompt is thin glue over the common skill | [SYS §Major Parts 2; DSG §4; FR-013/014] |
| **M3 `worker`** | execution | One goal-driven background subagent per claimed ticket; objective exactly *finish this ticket*: produce the stage product, record it as an artifact, set `in-review`. Narrow brief (ticket + direct basis artifacts + skills + lane constraints); never accepts, never wanders. | Native background subagents (opencode 2.3.0, idle-reported); the spawn prompt IS the brief | [SYS §Major Parts 3; §Interfaces 2; DSG §5; FR-017/018] |
| **M4 `verdict`** | governance | Reviews each `in-review` ticket on store evidence and runs the central quality gate on the shared tree; the ONLY `done` authority. Neither worker nor ticker grades itself. | The engine review pass + the per-project quality gate (gate configuration is project-owned — AGENTS.md "The flow and the final check"; `eclipse/QUALITY.md` for the eclipse reactor) | [SYS §Major Parts 4; DSG §8; FR-021] |
| **M5 `process`** | knowledge (static) | The shared procedure and per-stage discipline knowledge: one common `stage-pipeline` skill parameterized per stage by division map + discipline skill. Loaded by actors; never an active process. | `.opencode/skills/stage-pipeline/SKILL.md` + the per-stage skill family (`software-*`, `test-software-*`; the `VStages.SKILL` mapping) | [SYS §Major Parts 3 (the brief carries "its stage skill + the common `stage-pipeline` skill"); DSG §6; FR-016] |

```text
[M5 process]  (static knowledge: stage-pipeline + discipline skills)
   ▲ E5            ▲ E6
   │ loads         │ loads
[M2 ticker] ─E4 spawn─► [M3 worker]           [M4 verdict]
   │ E1                   │ E2                  │ E3 read + done     │ E7 gate
   ▼                      ▼                     ▼                    ▼
┌───────────────────────────────────────────────┐      ┌────────────┐
│ [M1 store] tickets · links · readiness ·      │      │ repo tree  │
│             claims · history (with actors)    │      └────────────┘
└───────────────────────────────────────────────┘
```

### System part → module mapping

Every system part from [SYS §Major Parts] maps to **exactly one primary
module**; cross-cutting concerns are noted without new owners:

| System part (basis) | Primary module | Note |
|---|---|---|
| Task store | **M1** | the stationary tickets the overview names alongside the four actors [SYS §Overview] are data owned by M1, not an actor |
| Per-stage ticker | **M2** | |
| Goal-driven worker subagents | **M3** | |
| Engine review pass + quality gate | **M4** | |

Cross-cutting (each anchored in an existing module, used by all actors):

- **History-with-actor** — an M1 property (every verb writes a history
  event with its actor); it is what makes each D-point decidable from the
  store [SYS §Interfaces 1]. Relied on by M2, M3, M4; owned by M1 only.
- **Spawn cap + lane serialization** — admission control held by M2 that
  governs M3 spawns (global cap, default 4; overlapping file lanes
  serialize within the stage) [DSG §5; FR-015].
- **File-lane discipline** — a store convention (labels/notes on the
  ticket), not git plumbing; constrains both M2's spawning and M3's edits
  [DSG §5].
- **Artifact carrying** — an M1 link property (division carries the
  upstream's artifact references so the chain carries content, not just
  IDs), consumed when M2 composes the M3 brief [SYS §Interfaces 3; FR-003].

## Dependency rules (default-deny)

Layer rule: dependencies point **toward the substrate or static knowledge
only** — control and execution may depend on substrate + knowledge;
governance depends on substrate (plus the repo tree for the gate);
substrate and knowledge depend on nothing. **Default deny: an edge exists
only if listed here.**

| Edge | Direction | Interface (verbs / mechanism) | Basis |
|---|---|---|---|
| E1 | M2 → M1 | `task_readiness`, `task_plan_sprint`, `task_claim`, division/link creation, `task_close_sprint`; `task_set_blocked` escalation of a blocked ticket onto its basis | [SYS §Interfaces 1; DSG §4; FR-009] |
| E2 | M3 → M1 | `task_get`, `task_add_artifact`, `task_update` (→ `in-review`), `task_add_comment`, `task_set_blocked` | [SYS §Interfaces 1; DSG §5] |
| E3 | M4 → M1 | `task_get` / `task_traceability` reads + the `done` transition | [SYS §Interfaces 1; DSG §8] |
| E4 | M2 → M3 | Subagent spawn: one background subagent per claimed ticket, under the global cap, serialized on overlapping lanes; the spawn prompt is the brief; the worker reports on idle — no reverse channel | [SYS §Interfaces 2; DSG §5] |
| E5 | M2 → M5 | Loads the common `stage-pipeline` skill only (process, no discipline work) | [DSG §6] |
| E6 | M3 → M5 | Loads the common skill **+ its stage's discipline skill** (common + stage) | [SYS §Major Parts 3; DSG §6] |
| E7 | M4 → repo tree | The central quality gate (build/tests/lint) runs on the shared tree after each landing | [SYS §Major Parts 4; DSG §8] |

Denied edges (each with its structural reason — these are architecture
rules, not conventions):

- **M1 → anything**: the store is a passive substrate — no callbacks, no
  notifications; discovery is by read at the tick boundary [SYS §Overview:
  "the tick boundary is the only synchronization point"; NFR-002].
- **M3 → M2**: a worker never receives mid-flight orders and never reports
  into the ticker session; its only report is the idle return over the
  spawn mechanism [DSG §5].
- **M3 ↔ M3, M2 ↔ M2 across stages**: stages never call each other — no
  direct handoffs; the store is the only channel [SYS §Interfaces 3; DSG
  §4; NFR-002].
- **Any actor → store files directly**: write discipline — every store
  write goes through the `task_*` verbs; hand edits are not an architecture
  edge (the store convention permits only the smallest documented
  correction, verified with `task_get` + `task_doctor` and recorded as a
  ticket comment) [SYS §Major Parts 1 "no hand edits"; AGENTS.md write
  discipline].
- **M2 / M3 → `done`**: the `done` transition is reachable only from the
  engine review context (M4) — structurally, not conventionally; the
  worker's write surface is limited to its own ticket (status flips +
  artifact recording) [FR-017/FR-021; REQ §D-3; A-003 ticket].
- **M2 / M3 → the gate**: workers and tickers never run the quality gate to
  claim completion; the gate is M4's input only [DSG §8].

Direction consistency: M1 and M5 have **zero outgoing edges**; every
actor's only outbound channel is toward the store (plus the single spawn
edge E4 and the gate run E7). The graph is acyclic by construction — no
edge ever points up-layer or from the substrate outward.

## Interfaces to the real code

Where each module meets this repo's actual machinery (the acceptance chain
lives here):

- **M1 `store`** — `.opencode/tasks/hephaestus/` (ticket files, `_meta.json`)
  and the `task_*` verbs, served as MCP tools: the `tasks` stdio launcher
  (`eclipse/tasks-tools.ps1`, configured in `opencode.json`) for TUI-only
  sessions, or the `eclipse-build` endpoint when Eclipse runs; wire names
  stay `task_*` [AGENTS.md §The ticket / sprint workflow]. Invariants the
  module guarantees: append-only history with actors, done-time artifact
  guard, atomic claim as the only serialization point [FR-002; SYS
  §Interfaces 1].
- **M2 `ticker`** — one `experimental.schedules` schedule per stage (W1,
  default 15 min/stage, dormant when OFF), armed/disarmed by
  `.opencode/skills/stage-pipeline/ticker-setup.ps1`; rolling sprint records
  `ticker-<stage>` reopened at kickoff and closed at the tick boundary
  (U-108 reopen semantics) [DSG §4; FR-013/014].
- **M3 `worker`** — native background subagents (2.3.0 semantics: spawn
  returns immediately, reports on idle); the ticker composes the narrow
  brief into the spawn prompt; the worker needs write access only for its
  own ticket [DSG §5; FR-017/018].
- **M4 `verdict`** — the engine review pass (reads ticket + artifacts +
  history; verdicts on store evidence) and the project's quality gate run
  on the shared tree; gate configuration is per project lane, never
  re-decided here [DSG §8; AGENTS.md "The flow and the final check"].
- **M5 `process`** — the skill files themselves; skills are the carriage of
  process change: `stage-pipeline` changes once and every stage inherits it
  [DSG §6].

## Pass-through: where the design already fixes the structure

Per this stage's contract, where [DSG] already fixes structure this doc
records the mapping and the pass-through rationale — not new structure:

| [DSG] fixes | Recorded here as | Pass-through rationale |
|---|---|---|
| §1 the model (actors, roles, never-does column) | The M1–M4 module set, its layers, and the edge rules | The design's model diagram IS the actor structure; the architecture only names the modules and makes the edges explicit + default-deny |
| §2 ticket contract (`stage:` immutability, done-time guard, division trigger) | M1 responsibilities (substrate row) | Store semantics are decided; M1 owns them wholesale, nothing architectural to add |
| §3 link contract (epic = scope, basis = derivation, paired test at division) | M1's link model + the D-2 flow | The two-relations model is research-decided; the architecture encodes it as M1 data + E1 verbs |
| §4 the tick algorithm (SCAN → BLOCKED → DONE → READY → CLOSE) | M2's fixed procedure (a module with no decisions) | "Determinism is the point" [DSG §4]; the architecture adds only the module boundary and E1 |
| §5 fulfillment (narrow brief, cap, lane serialization, no isolation) | M3 + edge E4 + AD-004/AD-005/AD-007 | Admission and brief semantics are decided; E4 encodes them as exactly one directed edge |
| §6 stage skills (one common skill, parameterized per stage) | M5 + edges E5/E6 | "One process, ten parameterizations — not ten divergent copies" [DSG §6] — a static knowledge layer, not a runtime actor |
| §7 artifacts (artifacts store W12 or repo-path fallback) | M1's artifact index + carried references (FR-020) | "The ticket's artifact list stays the store-side index" [DSG §7]; ref shape is a local setting, not structure |
| §8 the verdict (`in-review` by worker; engine-only `done`) | M4 + E3/E7 + AD-003 | The verdict model is decided; M4 owns it; the architecture states it as an unreachable-transition rule |

## D-point evidence flows through the architecture

Each demonstration point from [REQ §Demonstration contract] traced through
the module chain to its store evidence — traceable one-to-one to the
[SYS §End-to-end data flows] walks and the [SYS §D-point → parts / evidence
map]:

- **D-1 Stationary fulfillment** (run instance: T-031) — (1) M2's tick SCAN
  reads readiness via E1 and finds the ticket READY; `task_claim` sets
  in-progress (history: claim, actor ticker). (2) Over E4, M2 spawns one M3
  worker with the brief. (3) M3 produces the stage product, records it via
  E2 `task_add_artifact` (history: artifact add by worker), then E2
  `task_update` → `in-review` (history: transition by worker). (4) M1
  stamps every step as a history event with its actor; `stage:` is never
  rewritten (M1 immutability). Evidence chain: **claim → artifact →
  in-review, with actors, on a ticket whose `stage:` never moved; no `done`
  anywhere in the chain** [SYS §data flows D-1; REQ §D-1]. (This ticket,
  A-003, is itself being fulfilled through this exact flow.)
- **D-2 Division + linking** (run instance: T-031 → A-001; continuing live:
  A-001 → A-003 + pairs A-002/A-004) — (1) M4 sets the basis `done` (E3) —
  the trigger. (2) The downstream stage's M2, at its next tick's DONE step,
  runs its division map via E1: creates the child with `epic` = upstream
  epic, `basis` = the upstream id, the upstream's artifact references
  carried over. (3) M1's readiness walks the basis chain: the child flips
  WAIT_UPSTREAM → READY once the basis is done. (4) The downstream M2
  kickoff claims and fulfills the child as in D-1. Evidence chain: **the
  child's `basis`/`epic` + carried artifacts; readiness before/after the
  basis done; `task_traceability` basis-pairing rows** [SYS §data flows
  D-2; REQ §D-2; FR-003/FR-004].
- **D-3 Engine verdict** (run instance: A-001 — done 2026-11-14 "product
  PASS, lint clean" by engine review) — (1) M3 stops at `in-review` (E2) —
  its last write. (2) M4 reviews the ticket + artifacts on store evidence
  (E3 reads) and runs the central gate (E7). (3) Only M4 performs the
  `done` transition (E3) — structurally unreachable for M2/M3 (denied-edge
  rule). Evidence chain: **history carries `in-review` (worker actor) then
  `done` (engine actor); no worker/ticker `done` event anywhere in the
  chain** [SYS §data flows D-3; REQ §D-3].

| D-point | Module chain that generates its evidence | Store evidence (decidable by M4 reading M1 alone) | Run instance |
|---|---|---|---|
| D-1 | M2 (claim + spawn, E1/E4) → M3 (product + artifact + in-review, E2) → M1 history | claim → artifact → `in-review` events with actors; `stage:` immutable | T-031 |
| D-2 | M4 (done trigger, E3) → M2 (division, E1) → M1 (links + readiness) | child's `basis`/`epic` + carried artifacts; WAIT_UPSTREAM → READY | T-031 → A-001; A-001 → A-003, A-002, A-004 |
| D-3 | M3 (stop at `in-review`) → M4 (sole `done`, E3/E7) | `in-review` by worker, then `done` by engine; no other `done` | A-001 |

The pass/fail bar reads directly off this table: PASS iff each row's store
evidence holds with no manual store hand-edits in any history [REQ
§Pass/fail criteria; SYS §Key trade-offs].

## Key quality attributes

- **QA-1 Evidence decidability** — every D-point decidable from store
  evidence alone [REQ §Pass/fail; SYS §Overview]. Structure: all writes
  flow through M1 verbs that stamp history with actors (E1/E2/E3); the
  verdict reads only M1 — there is no side channel that could produce
  undecidable evidence.
- **QA-2 Decoupling** — stages communicate only through the store; the tick
  boundary is the only synchronization point [NFR-002; DSG §4]. Structure:
  the default-deny edge list contains no lateral actor edge at all.
- **QA-3 Determinism** — the tick algorithm is identical across all ten
  stages; only per-stage data differs (division map, skill, artifacts)
  [NFR-001; DSG §4]. Structure: M2 is a fixed procedure; M5 is the only
  per-stage parameterization (E5/E6), and M5 is data, not code paths.
- **QA-4 Recoverability** — no worktree isolation (accepted risk, owner
  2026-11-14); recovery from a broken run is git revert; the gate + commit
  close the loop [NFR-005; SYS §Key trade-offs; DSG §5]. Structure: workers
  edit the shared tree; the single M4 gate is the loop's closing authority.

## Architecture decisions

- **AD-001 Blackboard over the task store, driven by a fixed tick** — the
  store-as-shared-substrate is exactly the basis's "task store plus four
  actors" [SYS §Overview]; layered/client-server/event-driven rejected
  (§Chosen pattern). Trade-off: the store is the single trust and
  bottleneck point — atomic `task_claim` is the only serialization, and
  parseability invariants live there.
- **AD-002 The store is the only shared substrate; tickers and workers
  never link directly** — all coordination is store-mediated [A-003
  ticket; SYS §Interfaces 3; DSG §4]. Trade-off: coordination latency —
  cross-stage work waits for tick boundaries by design.
- **AD-003 The done authority is structural, not conventional** — only the
  engine review context (M4) can reach `done`; the worker's write surface
  is limited to its own ticket [FR-021; REQ §D-3]. Trade-off: throughput —
  every ticket pays one review hop.
- **AD-004 Admission is one knob** — the global spawn cap (default 4) plus
  per-stage serialization on overlapping file lanes [DSG §5; FR-015].
  Trade-off: coarse (no per-stage caps) in exchange for a single
  owner-adjustable control.
- **AD-005 The brief is narrow; content travels in artifacts** — a worker's
  world is its ticket + direct basis artifacts + skills + lane; transitively
  needed content must live IN the basis artifact (the chain carries
  content, not just IDs) [DSG §5; FR-018; FR-003]. Trade-off: basis docs
  must be self-sufficient; a worker that needs store-wide answers blocks
  back to its basis instead of wandering.
- **AD-006 Pass-through over re-decision** — where [DSG] fixes structure,
  this doc records the mapping (§Pass-through) [this stage's AC-1].
  Trade-off: two documents to read; kept one-directional — the architecture
  cites the design and never re-decides it.
- **AD-007 No isolation; git revert is the recovery** — workers edit the
  shared tree; the gate + commit close the loop [NFR-005; SYS §Key
  trade-offs]. Trade-off: a broken worker can damage the tree until the
  gate catches it — accepted (owner 2026-11-14).

## Open questions

None at the architecture level. The open items inherited from the
requirements baseline are cost knobs, not structure — Q-001 (final ticker
interval/cap values; defaults 15 min / 4 carried) and Q-002 (Board switch
relabeling, a chunk-5 UI decision) [requirements-stage-pipeline.md §Open
Questions]. They change edge E4's parameters, never the edge set.

## Coverage

What this architecture covers, traced to its basis and above:

- **System parts** SP-1..SP-4 ([SYS §Major Parts]) → M1..M4, one primary
  module each (§Modules); the overview's stationary tickets map to M1 as
  data.
- **Demonstration contract** D-1/D-2/D-3 ([REQ §Demonstration contract]) →
  §D-point evidence flows; one-to-one with [SYS §D-point → parts / evidence
  map].
- **Store contract** FR-001 (basis field) → M1 link model; FR-002
  (done-time guard) → M1 + E3; FR-003/FR-004 (division + paired test
  ticket) → E1 + D-2; FR-005..FR-007 (readiness from basis chains) → M1.
- **Movement retirement** FR-008 (no wandering verbs) → the denied-edge
  list (no edge exists for them to travel on); FR-009 (blocked escalates to
  the basis) → E1 + M2's BLOCKED step.
- **Ticker substrate** FR-013/FR-014 (one scheduled ticker per stage, fixed
  lifecycle) → M2; FR-015 (cap + lane serialization) → E4 + AD-004; FR-016
  (common skill, parameterized) → M5 + E5/E6.
- **Fulfillment** FR-017/FR-018 (one goal-driven subagent per ticket; the
  narrow brief) → M3 + E4 + AD-005; FR-019 (this acceptance run, evidence
  on U-111 with engine verdict) → the whole doc.
- **Artifacts + verdict** FR-020 (artifacts store or repo-path refs) → M1's
  artifact index; FR-021 (engine-only done) → M4 + AD-003.
- **NFRs** NFR-001 → QA-3; NFR-002 → QA-2 + the denied edges; NFR-003
  (autonomy, NEEDS-HUMAN as the only human surface) → M2's BLOCKED
  escalation + M1's blocker state; NFR-004 (cost observability via native
  stats) → E4's cap decisions, no separate module [DSG §5]; NFR-005 →
  QA-4 + AD-007.
- **Not covered (out of scope per [REQ §Out of scope])**: performance,
  cost, and multi-fan-out validation — later runs; nothing here constrains
  them beyond noting division is fan-out-capable in M1's link model.
