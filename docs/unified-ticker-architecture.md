# Architecture: the unified V-ticker (`ticker-v`)

- **About this document:** the architecture-level definition OF the unified
  V-ticker — the internal structure of the system fixed by its basis
  `docs/unified-ticker-system.md` (ticket T-055, done by engine verdict
  2026-11-15). It assigns every system part to exactly one primary
  architectural component, fixes the dependency rules between components as
  **default-deny edges**, names the interfaces where the components meet this
  repo's real machinery (the task store, the `stage-pipeline` skill, the
  native schedules/subagent surfaces), and traces every interaction-model
  element — I-1 schedule x steward, I-2 steward x workers/reviewers, I-3
  steward x store, I-4 global spawn cap, I-5 one rolling record — and every
  restart/re-arm scenario S-1..S-6 through the components to its evidence.
  Open questions Q-001..Q-004 stay ROUTED to their owning legs, not silently
  decided; the prototype deltas keep their Route A/B/C destinations.
  **Read by:** the design and implementation legs deriving from this ticket
  (T-103), the paired test-architecture ticket (minted by division at
  done-time), the stage tickers and workers this architecture describes, the
  maintainer. **Related:** `docs/unified-ticker-system.md` (the upstream
  basis — the parts, interaction model, invariants and scenarios this
  architecture structures; cited as [SYS]), `docs/unified-ticker-requirements.md`
  (the governing requirements — FR-001..FR-015, NFR-OPER/REL/COST-001,
  constraints C-A..C-F; cited as [REQ]), `docs/design-stage-pipeline.md`
  (the U-110 design — its store contract, link contract, readiness, verdict
  and fulfillment rules carry over; its per-stage scheduling topology is
  SUPERSEDED on the ten→one point [SYS §Supersession]; cited as [DSG]),
  `.opencode/skills/stage-pipeline/SKILL.md` (the one-pass tick procedure the
  steward executes; cited as [SKILL]),
  `.opencode/skills/stage-pipeline/ticker-setup.ps1` (the prototype arming
  script, read-only provenance; cited as [SETUP]); ticket T-103 (this stage,
  epic U-112, basis T-055).

## Scope

The unified ticker is not a program — it is **the task store plus three
runtime actors around it** [SYS §Overview]. This is therefore a
**process/agent architecture**: its components are components of the system,
each anchored to a real surface of this repo (the store directory, the skill
files, the schedule record, the arming script, the subagent machinery), not
to a package or library lane. The architecture question this doc answers is:
*which component owns which responsibility, who may write to whom through
which interface, and through which component chain each I-point and S-point
traces to its evidence*. Everything structural that [SYS] or [DSG] already
fixes is recorded as a mapping (§Pass-through), not re-decided.

## Chosen pattern

Evaluated against the system definition: a **layered** pattern alone would
imply call stacks between tiers that the system forbids (stages never call
each other; the tick boundary is the only synchronization point [SYS §The
tick as a system event; DSG §4]); **client-server** captures only the verb
interface and misses the actor structure around the store; **event-driven**
would wrongly license reactive links — the system has no bus; "events" are
store history read at the tick boundary; **microservices/SOA/P2P** are
distributed shapes for a system whose whole point is ONE schedule, ONE
steward, ONE record ([REQ] FR-001/FR-012).

Chosen: **blackboard over the task store, driven by ONE fixed control
cadence.** One shared data substrate (the tickets and the rolling sprint
record, mutated only through the `task_*` verbs [SYS §Major parts]) with
independent actors around it — exactly [SYS §Overview]'s "the task store
plus three runtime actors around it" — and one control loop whose procedure
is fixed ([SKILL]; [DSG] §4 "determinism is the point"). This is the pattern
the basis describes; the architecture's job is to name its components and
make its edges explicit and default-deny. The one deliberate difference from
the sibling acceptance-run architecture: the control cadence is not per
stage — ONE schedule walks the whole V [SYS §Supersession], so the control
component set collapses from ten tickers to one steward.

## Components and layers

| Component | Layer | Responsibility (from the basis) | Anchored in (real machinery) | Basis |
|---|---|---|---|---|
| **C1 `store`** | substrate | The ONLY memory: tickets with immutable `stage:` ownership, `epic` (scope) / `basis` (derivation) links, claims, artifact indexes, append-only history, readiness semantics — plus the ONE rolling sprint record `ticker-v` (U-108 reopen semantics). Mutated only through the `task_*` verbs; never loses a confirmed write. | `.harness/tasks/hephaestus/` (one Markdown file per ticket) + the `task_*` MCP verb surface | [SYS §Major parts "task store" + "rolling sprint record"; I-3; I-5] |
| **C2 `host`** | platform | The opencode service runtime: the `experimental.schedules` surface (W1: list/get/create/pause/resume/delete), session execution, background-subagent execution, and the `tasks` MCP transport. Holds everything that dies on restart (schedules, live sessions, the cap count, the MCP connection). Provides surfaces and transport only — no agency of its own. | the running service (url + token from `service.json`, Basic auth — the same discovery [SETUP] uses) | [SYS §Major parts "opencode service"; remember-vs-forget] |
| **C3 `schedule`** | control-data | The ONE `ticker-v` experimental-schedule record: the name is the identity key (INV-1); bound to the steward session; carries the arm-time tick prompt (canonical stage list + Cap interpolated — the cap reaches the steward through the prompt and nowhere else, C-F); service-maintained health fields `runCount` / `lastOutcome`. Runtime state — dies with C2. | the schedule record on C2; created/looked-up only by C8 | [SYS §Major parts "ticker-v schedule"; I-1] |
| **C4 `steward`** | control | The tick executor: ONE pass over all ten stages in canonical order — BLOCKED escalate to basis → IN-REVIEW verdict application → DONE divide → READY plan-then-claim under the cap → gated pass close — plus reconciliation of interrupted work at the first pass after a resume, count-before-spawn + defer-at-cap, and the mid-pass store-loss reconnect. Holds the big picture and the cap counter; never works a ticket itself and never accepts one. | the steward session titled `ticker-v`, created as a CHILD of a repo-rooted parent (C-C); the procedure is [SKILL] | [SYS §Major parts "steward session"; I-2..I-4] |
| **C5 `worker`** | execution | One background subagent per claimed ticket (FR-008); its world is exactly the fulfillment brief: ticket + direct basis artifacts + its stage's discipline skill + lane constraints. Store footprint is exactly its own ticket: record the artifact, set `in-review`, comment, block when stuck. Never `done`, never another ticket, never spawns. | native background subagents (spawn returns immediately, reports on idle) | [SYS §Major parts "worker subagents"; I-2; DSG §5] |
| **C6 `reviewer`** | governance | One read-only subagent per `in-review` ticket per stage visit (FR-006 step 2); judges the ticket + its recorded artifacts (and runs acceptance/lint gates via bash; file edits denied) and DELIVERS the engine verdict: PASS → `done`, FAIL → blocked, UNCLEAR → one retry round-trip. Delivers only — the verdict WRITE is the steward's, in the tick's fixed write order (I-3; AD-005). | the `reviewer` agent (`.opencode/agent/reviewer.md`, `edit: deny`; "the engine applies it, you stay read-only") | [SYS §Major parts "reviewer subagents"; I-2; I-3] |
| **C7 `process`** | knowledge (static) | The procedure and discipline knowledge: the common `stage-pipeline` skill (the tick procedure + the fulfillment loop + boundaries) parameterized by the per-stage discipline skills (`software-*`, `test-software-*`). Loaded by actors; never an active process. | `.opencode/skills/stage-pipeline/` + the discipline skill family | [SYS §Related; DSG §6] |
| **C8 `arming`** | setup | The ONLY path that mutates runtime topology: `ticker-setup.ps1` creates/looks up the steward session (child of a repo-rooted parent) and the `ticker-v` schedule, with arm-time parameters (`-IntervalMinutes` default 15, `-Cap` default 4 — C-F) interpolated into the tick prompt; resume = re-running it alone (FR-015). REQUIRED behavior: exactly ONE schedule and ONE steward on re-arm (the guard — Route A); never touches store business state; no per-stage mode. | `.opencode/skills/stage-pipeline/ticker-setup.ps1` (prototype: it POSTs unconditionally today — delta #1; hardening is Route A's content, not re-specified here) | [SYS I-1; S-3/S-4; §Route A] |

```text
 [C7 process]   (static knowledge: stage-pipeline + discipline skills)
    ▲ E8                 ▲ E9
    │ loads procedure    │ loads common + stage skill
 [C4 steward] ──E4 spawn──▶ [C5 worker]
    │     │                          │
    │     └──E4 spawn (reviewer)──▶ [C6 reviewer]
    │                                    │ E6 store reads
    │ E3 read/write (task_* verbs,      │ E7 runs gates (repo tree, edit-denied)
    │    the tick's fixed order)        │
    ▼ E5 own-ticket writes ─────────────┼──▶ (C5 writes, C6 reads)
 ┌─────────────────────────────────────────────────────────────┐
 │ [C1 store] tickets · claims · epic/basis links · artifacts  │
 │            · history · ONE record "ticker-v" (U-108 reopen) │
 └─────────────────────────────────────────────────────────────┘
        ▲ E3/E5/E6 ride the store transport it hosts
 [C2 host: opencode service] ◀── E1 arm (exactly-one guard) ── [C8 arming]
 [C3 schedule "ticker-v"] ──── E2 fires the arm-time tick prompt ──▶ [C4 steward]
 (runtime record — dies with C2)                              ▲
                                                    [PO] operator ── E10 arm/pause/resume,
                                                    E11 NEEDS-HUMAN resolution
```

### System part → component mapping

Every major part from [SYS §Major parts] maps to **exactly one primary
component**; cross-cutting concerns are noted without new owners:

| System part (basis) | Primary component | Note |
|---|---|---|
| The opencode service (runtime host) | **C2** | platform, native; the host is where the rest runs, not an actor |
| The `ticker-v` schedule | **C3** | control-data: a record hosted on C2, created only via C8 |
| The steward session | **C4** | the single control actor |
| Worker subagents | **C5** | |
| Reviewer subagents | **C6** | |
| The task store | **C1** | |
| The rolling sprint record `ticker-v` | **C1** | the record is C1 DATA [SYS I-5]; its lifecycle (plan-reopen, gated close) is driven only through C4's E3 writes |
| The PO / kickoff assistant | outside the runtime loop | operator role only: arms/pauses/resumes via C8 (E10) and resolves NEEDS-HUMAN in C1 (E11) — never part of the tick |

Cross-cutting (each anchored in an existing component, used by several):

- **Remember-vs-forget** — the C1/C2 split IS [SYS]'s remember table [SYS
  §What the store remembers vs what the service forgets]: tickets, links,
  claims, artifacts, history and the rolling record survive everything;
  schedules, sessions, the cap count and the MCP connection die with C2.
  Total by design (NFR-REL-001); INV-6 rests on it.
- **Session-tree depth** — repo-rooted parent → steward → subagents; the
  steward is one subagent level, its spawns the second (C-B):
  `experimental.subagent_depth >= 2` must hold, setup-local per D-004 — a
  constraint on E4, documented wherever the unified ticker is described
  (Route C), never a new component.
- **Lane serialization** (FR-009) — admission control inside E4: the steward
  never spawns two subagents whose file lanes overlap; file-lane discipline
  is a store convention (labels/notes on the ticket), not git plumbing
  [DSG §5].
- **Mid-pass store loss** — reconnect is a C4 duty riding E4's host
  interface (`POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`,
  url + token from `service.json`), then continue the pass where it stopped
  [SYS I-3; SKILL §Field observations].

## Dependency rules (default-deny)

Layer rule: dependencies point **toward the substrate, static knowledge or
platform only** — control and execution may depend on substrate + knowledge
(+ the platform that hosts them); governance (C6) depends on the substrate
it reads and the tree it gates; substrate, knowledge and platform have no
actor edges of their own. **Default deny: an edge exists only if listed
here.**

| Edge | Direction | Interface (verbs / mechanism) | Basis |
|---|---|---|---|
| E1 | C8 → C2 | Arming: create the steward session (`POST /api/session`, child of a repo-rooted parent, C-C) + create/look-up the `ticker-v` schedule by name with the arm-time prompt (interval, Cap interpolated). REQUIRED: exactly-one on re-arm — reuse or retire-and-recreate, never duplicate (the guard is Route A's content; Q-001/Q-002 ride it). | [SYS I-1; S-3; FR-003; C-F] |
| E2 | C3 → C4 | Firing: when the interval elapses, C2 delivers the arm-time tick prompt into the steward session as a new turn. No state carried between ticks in the prompt or the schedule — each firing is a complete, independent pass. | [SYS I-1; FR-002; FR-005] |
| E3 | C4 → C1 | The tick's store interface. Read path: `task_readiness` per stage (an empty V costs exactly one query per stage, FR-014) plus `task_get` / `task_list` / `task_board` for verdicts and division. Write path, in the tick's fixed order (FR-006): escalate (`task_set_blocked` on the *basis* ticket) → verdict application (`task_update` → `done` on the reviewer's PASS only; FAIL → `task_set_blocked`; UNCLEAR → one retry round-trip to the fulfiller, riding E4) → division (`task_create` with `stage`/`epic`/`basis`, artifact references carried, paired `test-*` for definition stages) → kickoff (`task_plan_sprint` THEN `task_claim` — INV-4's ordering) → gated close (`task_close_sprint` only when nothing is claimed or in flight — I-5). Plus reconciliation writes: `task_release` of orphaned claims (S-2). One attempt per ticket per tick. | [SYS I-3; FR-006/007; DSG §2/§3] |
| E4 | C4 → C2 | Subagent spawn: workers (one per claimed ticket, the fulfillment brief) and reviewers (one per `in-review` ticket per stage visit, skipping tickets owned by the steward's own running children); background, reports on idle — no reverse channel. Admission: count-before-spawn ≤ Cap counting BOTH workers and reviewers; defer-at-cap plans the ticket into the rolling record but does NOT claim it (I-4); overlapping lanes serialize. Also carries the store-loss reconnect. | [SYS I-2; I-4; FR-008/009/010/011] |
| E5 | C5 → C1 | Own-ticket footprint only: `task_add_artifact`, `task_update` → `in-review`, `task_add_comment`, `task_set_blocked` when stuck. `done` is unreachable (structurally excluded from the write set, FR-008). | [SYS I-2; DSG §5] |
| E6 | C6 → C1 | Read-only store reads: the ticket, its artifacts, its history. No writes — the verdict travels back over E4's spawn mechanism and is applied by C4 (AD-005). | [SYS I-2; I-3; reviewer.md] |
| E7 | C6 → repo tree | Runs the acceptance command / lint gates via bash (read + execute); file edits denied. | [SYS I-2; reviewer.md] |
| E8 | C4 → C7 | Loads the common `stage-pipeline` skill only (the procedure; no discipline work). | [DSG §6] |
| E9 | C5 → C7 | Loads the common skill + its stage's discipline skill (common + stage). | [DSG §6; FR-008] |
| E10 | PO → C8 | The operator's one-command arm/pause/resume (FR-015's command; the W1 pause/resume/delete are the explicit disarm path, the organic pause is shutting the machine down). | [SYS I-1; FR-015] |
| E11 | PO → C1 | NEEDS-HUMAN resolution: clear the blocker; the ticket returns to the pump. | [AGENTS.md escalation surface; DSG §4 step 2] |

Denied edges (each with its structural reason — these are architecture
rules, not conventions):

- **C1 → anything**: the store is a passive substrate — no callbacks, no
  notifications; discovery is by read at the tick boundary [SYS §The tick as
  a system event].
- **C2 → anything as agency**: the host provides surfaces and transport
  (the `tasks` MCP namespace is the carrier of E3/E5/E6), but it initiates
  no store write and makes no ticket decision — hosting is not an actor
  edge. The service-maintained health fields on C3 are recorded telemetry,
  not decisions.
- **C5/C6 → C4**: no mid-flight orders, no reporting into the steward
  session; the only return channel is the idle completion over the spawn
  mechanism (E4) [DSG §5].
- **C5 ↔ C5, C6 ↔ C6**: no lateral subagent edges; a worker's store
  footprint is exactly its own ticket — it never touches another ticket
  [SYS I-2].
- **C4 → discipline work / bare `done`**: the steward never works a ticket
  and never accepts one; `done` is reachable only as the application of a
  reviewer PASS inside E3's fixed write order — never as C4's own judgment
  [SYS §Major parts "steward session"; FR-006].
- **C5 → `done`**: structurally excluded from E5's write set; the verdict is
  the engine's, never the fulfiller's self-assessment [FR-008; SKILL
  §Fulfillment].
- **C6 → file edits**: edit-denied (`.opencode/agent/reviewer.md`); read +
  gate runs only (E6/E7).
- **C8 → C1**: the arming path never writes store business state —
  reconciliation of interrupted work is C4's first-pass duty (S-2/S-4), not
  setup's. Setup stays trivially side-effect-free on the store.
- **Any actor → store files directly**: write discipline (C-E) — every store
  write goes through the `task_*` verbs; hand edits are not an architecture
  edge (the store convention permits only the smallest documented
  correction, verified with `task_get` + `task_doctor` and recorded as a
  ticket comment) [AGENTS.md §Write discipline].
- **Any path → per-stage schedules**: the ten `ticker-<stage>` schedules
  stay retired; no arming or teardown path recreates them (FR-004, INV-7).
  C8 has no per-stage mode by construction — there is exactly one arming
  path and it cannot express ten schedules (Route B reconciles the doc
  leftover that still says otherwise).

Direction consistency: C1 and C7 have **zero outgoing edges**; C2's only
relations are hosting (E1/E2/E4 target its surfaces; it originates nothing).
Every runtime actor's outbound channel is toward the store (plus the single
spawn/gate mechanics over C2 and the tree). The graph is acyclic by
construction — no edge ever points from the substrate or the platform
outward as agency.

## Pass-through: where the basis already fixes the structure

Per this stage's contract, where [SYS] or [DSG] already fixes structure this
doc records the mapping and the pass-through rationale — not new structure:

| The basis fixes | Recorded here as | Pass-through rationale |
|---|---|---|
| [DSG §1] the model + §4 cadence/rolling-record topology (per-stage tickers, `ticker-<stage>` records, per-stage intervals) | **SUPERSEDED on the ten→one point** — not carried | [SYS §Supersession]: ONE schedule, ONE steward, ONE record replace the ten-schedule topology; the collapse is C3/C4 + C1's single record, nothing else |
| [SYS §Overview] "the task store plus three runtime actors" | the C1..C8 component set and the blackboard pattern | the basis names the structure; the architecture only names components, layers and default-deny edges |
| [SYS I-1] arming/firing/health/lifetime of the schedule | C3 + E1/E2 | interaction fixed; only the re-arm guard's MECHANISM stays open (Route A, Q-001/Q-002) |
| [SYS I-2] session tree, worker/reviewer lifetimes, lane serialization | C5/C6 + E4 + the depth constraint | lifetimes and footprints are fixed; no new mechanism |
| [SYS I-3] read/write paths, one attempt per ticket, store-loss reconnect | E3 (+ E4's reconnect) | the tick's fixed order IS the store contract; recorded verbatim |
| [SYS I-4] count-before-spawn, defer-at-cap, arm-time value | E4 admission + AD-004 | the cap is a rule of C4's procedure, not a surface |
| [SYS I-5] one rolling record, U-108 reopen, in-flight protection | C1 data + E3's gated close | lifecycle fixed; C1 owns the record, C4 drives it |
| [SYS §The tick as a system event] the ten-stage pass, FR-006 order | C4's procedure via C7/E8 | "determinism is the point" [DSG §4]; the steward is a fixed procedure, not a decision maker |
| [SYS §Invariants] INV-1..INV-7 | §Invariant anchors | each invariant mapped to the component/edge that preserves it — none re-derived |
| [SYS §Restart and re-arm scenarios] S-1..S-6 | §Scenario walks | the walks are re-traced through the components, not re-specified |
| [DSG §2] ticket contract (`stage:` immutability, done-time guard, division trigger) | C1 responsibilities, wholesale | store semantics are decided; C1 owns them, nothing architectural to add |
| [DSG §3] link contract (epic = scope, basis = derivation, paired test at division) | C1's link model + E3's division step | the two-relations model is research-decided; encoded as C1 data + E3 verbs |
| [DSG §5] fulfillment (narrow brief, cap, lane serialization, no isolation) | C5 + E4 + AD-004 | admission and brief semantics are decided; E4 encodes them as exactly one directed edge |
| [DSG §6] stage skills (one common skill, parameterized per stage) | C7 + E8/E9 | "one process, ten parameterizations — not ten divergent copies" — static knowledge, not a runtime actor |
| [DSG §7] artifacts store (W12, opt-in per D-004) | C1's artifact index; publication shape is a local setting | "the ticket's artifact list stays the store-side index" — ref shape is not structure |
| [DSG §8] the verdict (engine-owned `done`) | C6 + C4's verdict application + AD-005 | the verdict model is decided; on the unified walk it is delivered by per-ticket reviewer subagents and applied in the fixed write order |
| [DSG §11] native-first inventory | C2/C3 surfaces (schedules W1, background subagents, `usage.limits`-free admission) | no recreation of native primitives; the ours-remainder is C1, the cap and the engine verdict |
| [SYS §Technology choices] | the anchors column of §Components and layers | technology is settled at system stage; the architecture only anchors components to it |

## The interaction model through the components (I-1..I-5)

Each interaction-model element from [SYS §The interaction model], traced to
the components and edges that carry it:

- **I-1 Schedule x steward** — carried by **C3 + E1 + E2**. Arm-time data
  (canonical stage list, Cap) is interpolated into the prompt by C8 and
  reaches C4 through E2's prompt and nowhere else — so changing Cap or
  cadence means re-arming (C-F). Health (NFR-OPER-001) is read from ONE
  schedule record: `runCount >= 1`, `lastOutcome = fired` — never assembled
  from several. Exactly-one identity (FR-001/FR-003): the name `ticker-v` is
  the single identity key; E1's REQUIRED guard keeps one schedule and one
  steward after any re-arm — the mechanism (lookup-by-name reuse vs
  retire-and-recreate; steward reuse vs fresh) is Route A's content with
  Q-001/Q-002, constrained by S-3. Lifetime: C3 dies with C2; an orphaned
  steward (schedule deleted/paused, session alive) is dormant, not running.
- **I-2 Steward x workers/reviewers** — carried by **E4 + C5/C6 lifetime
  rules**. The session tree is three levels deep (C-B): repo-rooted parent →
  steward → worker/reviewer; `experimental.subagent_depth >= 2` holds
  setup-local (D-004) or the system cannot run — documented by Route C, a
  constraint on E4. Worker lifetime: background spawn, reports on idle,
  footprint exactly its own ticket (E5), never `done`. Reviewer lifetime:
  spawned in the pass's in-review step (skipping tickets owned by the
  steward's own running children — [SKILL] step 3), read-only (E6/E7),
  verdict delivered then idle. Worker death leaves its claim behind; the
  store cannot distinguish a dead claim from a slow one — reconciliation is
  C4's duty at the next tick (S-2). Lane serialization (FR-009) is E4
  admission.
- **I-3 Steward x store** — carried by **E3**. Read path: `task_readiness`
  per stage (empty-V cost: one query per stage, FR-014) plus
  `task_get`/`task_list`/`task_board`. Write path in the tick's fixed order:
  escalation → verdict application → division → plan-then-claim → gated
  close (E3's interface lists them in order). One attempt per ticket per
  tick — a UNCLEAR round-trip rides E4 once, then waits for the next tick.
  Mid-pass store loss: reconnect over E4's host interface and CONTINUE the
  pass; C1 never loses a confirmed write, and all invariants are phrased so
  that confirmed writes alone suffice to restore coherence.
- **I-4 The global spawn cap** — carried by **C4's decision procedure** (the
  arm-time prompt + [SKILL] boundaries): no surface enforces it. The cap is
  global *by construction* because exactly one steward exists (FR-001,
  US-002). Count-before-spawn (FR-010) counts C4's own live children — BOTH
  workers and reviewers; a child stops counting when it has delivered its
  completion (worker: artifact + `in-review` + idle; reviewer: verdict
  applied + idle) — the ticket it touched may still be `in-progress`/`in-review`
  in C1, but that is store state, not a running subagent. Defer-at-cap
  (FR-011): a spawn that would exceed Cap is deferred — the ticket is
  planned into the rolling record but NOT claimed; the next tick claims it
  first (claim implies spawn). Cap value: arm-time via E2's prompt (I-1);
  the counter is steward-instance-local and dies with C4 — the S-3 hazard.
- **I-5 The ONE rolling sprint record** — carried by **C1 (data) + E3's
  gated lifecycle**. One record for the whole pipeline (FR-012), reused
  across ticks — not per tick, not per stage (the superseded topology).
  Reopen semantics (U-108): planning into a CLOSED record reopens it
  (active, `closedAt: null`, `createdAt` preserved); planning into an ACTIVE
  record is a goal update only — the per-tick lifecycle is plan (reopens if
  closed) → claim → spawn → … → close at pass end. In-flight protection
  (FR-013): the record closes ONLY at a pass end where nothing is claimed or
  in flight; when work is in flight it stays ACTIVE across ticks and a later
  tick closes it with the digest. The record is store state — it survives
  restarts and re-arms untouched.

## Scenario walks (S-1..S-6)

Each restart/re-arm scenario from [SYS §Restart and re-arm scenarios],
re-traced through the component chain to its evidence — the coherence proof
that the invariants survive at the architecture level:

- **S-1 Steady-state tick** — C3 fires (E2) → C4 runs the pass (E8/E3
  reads) per stage in canonical order → plan-then-claim under the cap (E3)
  → E4 spawns C5 workers → they land artifacts and set `in-review` (E5) →
  C4 closes the record when nothing is in flight (E3's gated close). All
  invariants hold trivially. Evidence: C3's `runCount`/`lastOutcome`; the
  tickets' histories in C1; the record's digest.
- **S-2 Shutdown mid-pass (the pause case)** — C2 dies while workers run.
  Lost: C3, C4, C5, the cap count. Survived: every C1 write already
  confirmed — claims, `in-review` hand-offs, recorded artifacts, the record
  (active, because I-5 never closed it). On resume (E10: re-run C8 — E1's
  guard makes it exactly-one), C4's first pass reconciles from C1 state
  alone: (1) `in-review` tickets → normal verdict step (E4 reviewer spawn,
  E6 reads, E3 verdict application — reviewers judge on ticket + artifacts,
  unaware of the interruption); (2) claimed tickets whose fulfiller no
  longer exists → orphaned claims: C4 releases them (`task_release` →
  `sprint-backlog`) and re-claims + re-spawns them under the cap (E3/E4);
  the *detection mechanism* (bounded staleness vs session-existence probe)
  is a design-stage decision per [SYS S-2] — the architecture-level
  contract is fixed: an orphaned claim is released and re-queued, never
  silently left `in-progress` forever, never double-spawned while its
  worker might still be alive; (3) the record stays active until steps 1–2
  have drained (in-flight protection applies to interrupted work exactly as
  to live work). Nothing else needs repair: division-born tickets are found
  by readiness; the retired per-stage schedules stay retired.
- **S-3 Re-arm while armed (no restart)** — C8 runs on a service that
  already has `ticker-v`. REQUIRED (FR-003/AC-004): exactly ONE schedule and
  ONE steward afterwards — E1's guard (Route A; Q-001 mechanism, Q-002
  steward reuse-vs-fresh). The architecture-level hazard the guard must
  handle: the cap counter is steward-instance-local (I-4) — if re-arm
  REPLACES C4 while the old steward's children still run, the new steward
  starts counting at zero and INV-3 can be silently exceeded. E1 must
  therefore either REUSE the steward session (count preserved; Q-002's
  reuse option) or ensure the old steward's children drain before/while the
  new one spawns (Q-002's fresh option, with a drain rule). Route A carries
  this constraint explicitly.
- **S-4 Re-arm after restart (the resume case)** — FR-015: resume = E10
  alone (re-run `ticker-setup.ps1`). C1 remembers everything (the
  remember-vs-forget split); C4's first pass reconciles exactly as in S-2
  steps 1–3. No ticket, sprint, link or artifact state needs re-arming. The
  exactly-one outcome of the re-arm itself is FR-003 (S-3's guard).
- **S-5 Cap-starved pass** — more READY tickets than the cap allows: C4
  plans all of them into `ticker-v` (E3), claims and spawns only up to Cap
  (E3/E4), defers the rest unclaimed (`sprint-backlog`) — never exceeding
  (FR-010/FR-011). The next tick claims the deferred tickets first. Workers
  from earlier ticks count while alive, so a pass starting with live
  carry-over has correspondingly fewer free slots — the cap being global
  across ticks, not just within a pass.
- **S-6 In-flight at pass boundary** — the pass reaches its end while any
  ticket is claimed/in flight: E3's close is gated, never forced — the
  record stays ACTIVE (FR-013) and closes on a later pass with the digest.

## Invariant anchors (INV-1..INV-7)

| Invariant ([SYS §Invariants]) | Preserved architecturally by |
|---|---|
| INV-1 exactly ONE `ticker-v` schedule | C3's name as the single identity key + E1's exactly-one guard (Route A); no per-stage arming path exists (FR-004) |
| INV-2 exactly ONE steward session | the same E1 guard; steward reuse-vs-recreate decided at design (Q-002, constrained by S-3) |
| INV-3 at most Cap concurrent steward children, across the whole pass | E4's count-before-spawn + defer-at-cap (I-4); E1's drain-or-reuse constraint on steward replacement (S-3) |
| INV-4 plan-then-claim: no claim without a prior `task_plan_sprint` into `ticker-v` | E3's fixed write order (kickoff = plan THEN claim; claims take `sprint-backlog` only, division-born tickets start in `product-backlog`) |
| INV-5 ONE rolling record, never closed while claimed/in-flight | C1's single record + U-108 reopen lifecycle + E3's gated close (I-5) |
| INV-6 the store is the only memory; resume = re-running `ticker-setup.ps1` alone | the total C1/C2 remember-vs-forget split + C4's first-pass reconciliation (S-2/S-4); C8 writes no store business state |
| INV-7 the ten per-stage schedules stay retired | C8 is the only arming path and has no per-stage mode (FR-004); Route B removes the doc leftover |

## Key quality attributes

- **QA-1 One-command recoverability** (NFR-REL-001, FR-015) — a shutdown
  loses nothing that cannot be re-derived: the C1/C2 split is total, and
  every decision the next tick makes is made from C1 state alone. Structure:
  resume is E10 alone; reconciliation is C4's first pass; C8 has no store
  footprint to repair.
- **QA-2 Cost boundedness** (NFR-COST-001, US-002) — concurrent subagents
  (and therefore token burn) are bounded by ONE number. Structure: admission
  is one knob owned by C4, global by construction (one steward), armed
  through the prompt (C-F).
- **QA-3 Observability** (NFR-OPER-001) — pipeline health is readable from
  ONE schedule record (`runCount`, `lastOutcome`), never assembled from ten.
  Structure: C3 is a single record; nothing else claims to be the health
  surface.
- **QA-4 Determinism** ([DSG §4] carried over) — the tick algorithm is
  identical every launch; only data differs. Structure: C4 is a fixed
  procedure (C7/E8); the stage loop itself is data in the arm-time prompt —
  ten parameterizations collapsed into one loop with one data list.
- **QA-5 Confirmed-write coherence** ([SYS I-3]) — all invariants are phrased
  so that confirmed writes alone suffice to restore coherence; an aborted
  pass is the last resort. Structure: every state that matters between ticks
  lives in C1; the one deliberate runtime exception is the cap counter
  (steward-instance-local), and its loss is handled structurally by S-3's
  drain-or-reuse constraint on E1 — cost boundedness never depends on
  unrecorded state surviving.

## Architecture decisions

- **AD-001 Blackboard over the task store, driven by ONE fixed cadence** —
  the store-as-shared-substrate is exactly the basis's "task store plus
  three runtime actors" [SYS §Overview]; layered/client-server/event-driven
  rejected (§Chosen pattern). Trade-off: C1 is the single trust and
  bottleneck point — atomic `task_claim` is the only serialization point,
  and the tick boundary is the only synchronization point.
- **AD-002 One steward, one procedure, stages as data** — the ten-schedule
  topology's ten control components collapse into one C4 whose stage loop is
  the canonical list in the arm-time prompt [SYS §Supersession]. Trade-off:
  the whole V shares one context and one cadence — Q-003 (context fit at
  deep-V scale) and Q-004 (cadence defaults) are the priced risks, routed,
  never silently decided here.
- **AD-003 Pass-through over re-decision** — where [SYS]/[DSG] fix structure,
  this doc records the mapping (§Pass-through) [T-103 scope]. Trade-off:
  several documents to read; kept one-directional — the architecture cites
  the basis and never re-decides it.
- **AD-004 Admission is steward-local and arm-time** — no surface enforces
  the cap ([SYS §Technology choices]); the value travels in the prompt
  (I-1). Trade-off: the counter dies with the steward — re-arm replacement
  must drain-or-reuse (S-3's constraint on E1/Route A).
- **AD-005 The verdict is delivered by C6, applied by C4** — the reviewer is
  read-only and edit-denied (`.opencode/agent/reviewer.md`: "the engine
  applies it, you stay read-only"); the verdict write happens in C4's fixed
  write order ([SYS I-3]; the arm-time tick prompt [SETUP]). Trade-off: one
  hop per verdict; in exchange, `done` stays structurally unreachable for
  C5 and reachable for C4 only as verdict application — never as its own
  judgment.
- **AD-006 Reconciliation lives in the steward, not in setup** — C8 mutates
  runtime topology only; repair of interrupted work is C4's first-pass duty
  from C1 state alone (S-2/S-4). Trade-off: one reconciliation pass does all
  repair; the arming path stays trivially side-effect-free on the store,
  which keeps resume (FR-015) honest.
- **AD-007 The arming path is the single topology-mutating component, with
  no per-stage mode** — FR-004/INV-7 are enforced by construction: exactly
  one path exists and it cannot express ten schedules. Trade-off: none
  material; the prototype's unconditional POST (delta #1) is the deviation
  Route A removes, not a structure question.

## Open questions — routed, not decided

All open questions inherited from the basis stay on their owning legs; this
architecture adds only constraints, never answers:

| Question | Owning leg (unchanged) | Constraint this architecture fixes |
|---|---|---|
| Q-001 idempotency mechanism (reuse vs retire-and-recreate by name) | Route A — decided at design/implementation against the actual schedule API | E1's observable contract: exactly ONE schedule and ONE steward after any re-arm (INV-1/INV-2), whatever the mechanism |
| Q-002 steward-session accumulation (reuse vs fresh) | Route A — decided at design, constrained by S-3 | INV-3 must survive steward replacement: reuse keeps the cap count; fresh requires a drain rule so old children cannot blow the cap |
| Q-003 one-tick context fit at deep-V scale | design decision; flagged for the paired test (T-035 AC-2) | the contract never depends on one pass fitting the window — C1 carries all state; the fallback shape (a store-carried stage cursor + per-stage sub-passes) is a design-stage decision, not structure here |
| Q-004 cadence/cap defaults (`-IntervalMinutes 15`, `-Cap 4`) | owner call at arm time (C-F) | parameters of E1/E2 only — changing them is a re-arm, never a new edge |
| Dead-claim detection (bounded staleness vs session-existence probe) | design leg, per [SYS S-2] | the contract is fixed: orphaned claims are released and re-queued, never silently left `in-progress` forever, never double-spawned while the worker might still be alive |

Prototype deltas keep their Route A/B/C destinations [SYS §Downstream routes
for the prototype deltas]; this architecture only anchors them:

| Route | Destination (unchanged) | Architecture anchor |
|---|---|---|
| **A** — implementation-leg hardening of `ticker-setup.ps1` (deltas #1 re-arm dedupe-or-retire guard + #3 `$Password`→`$Token` fix) | the implementation stage of the ticker-v arc; file lane: the script only | C8 + E1's exactly-one guard, with S-3's cap-drain constraint (Q-001/Q-002) |
| **B** — skill lane: the stale `-Stages` pause/resume leftover in `SKILL.md` | a skill-lane ticket; file lane: `SKILL.md` only | C7 — reconcile to [SYS I-1] and S-4's unified truth (resume = re-run the script; the store is the memory) |
| **C** — coherence note: `experimental.subagent_depth >= 2` (delta #4's constraint half) | the lanes that own the configuration/coherence documentation (user-global config guidance per D-004; AGENTS.md ticker sections at their next coherence pass) | the depth constraint on E4 (C-B) — a note, not a code change, not a file lane of this arc |

At the architecture level there are no NEW open questions: every structural
choice above is fixed by the basis and recorded as a mapping; the routed
items above are mechanisms and parameters, not structure.

## Traceability

Governing FRs from `docs/unified-ticker-requirements.md`, and where this
architecture structures them:

| FR / NFR | Stated here |
|---|---|
| FR-001 (one schedule + one steward, named `ticker-v`) | C3 + E1 + INV-1/INV-2 anchors |
| FR-002 (the tick prompt drives the pass) | E2 (firing, arm-time prompt) |
| FR-003 (exactly-one on re-arm) | E1's guard, S-3 walk, Route A |
| FR-004 (no per-stage schedules; retirement) | INV-7 anchor + the denied per-stage edge; Route B |
| FR-005 (canonical ten-stage order) | C4's procedure via E8; the pass description |
| FR-006 (fixed per-stage processing order) | E3's write path (the fixed order) |
| FR-007 (plan-then-claim) | E3 kickoff step + INV-4 anchor |
| FR-008 (one subagent per claimed ticket; never done) | E4 spawn + E5 write set + AD-005 |
| FR-009 (lane serialization) | E4 admission + the cross-cutting lane rule |
| FR-010/FR-011 (global cap, defer at cap) | I-4 + E4 admission + INV-3 anchor + S-5 |
| FR-012/FR-013 (one rolling record, in-flight protection) | I-5 + C1 + INV-5 anchor + S-6 |
| FR-014 (empty pass = success, one query per stage) | E3 read path |
| FR-015 (resume = one command) | E10 + QA-1 + INV-6 anchor + S-2/S-4 |
| NFR-OPER-001 (one-record health) | QA-3 + I-1 |
| NFR-REL-001 (shutdown loses nothing) | QA-1 + the remember-vs-forget split |
| NFR-COST-001 (cost bounded by one number) | QA-2 + I-4 |
| Constraints C-A..C-F | C2 (C-A), the depth constraint on E4 (C-B), E1's parent anchoring (C-C), no provider/model ids anywhere (C-D), the task_*-only write rule (C-E), arm-time parameters in E1/E2 (C-F) |

T-103's acceptance criteria, addressed: **AC-1** — the architecture doc
exists with component structure, per-component responsibilities and explicit
(default-deny) dependency rules derived from [SYS] (whole document);
**AC-2** — every interaction-model element I-1..I-5 (§The interaction model
through the components) and every scenario S-1..S-6 (§Scenario walks) traces
through the components to its evidence; INV-1..INV-7 are anchored;
Q-001..Q-004 stay routed to their owning legs; prototype deltas keep their
Route A/B/C destinations (§Open questions — routed, not decided); **AC-3** —
the artifact is recorded on the ticket and the ticket moves to in-review;
done is never self-assessed (the engine's verdict decides). The paired
test-architecture ticket (minted by division at this ticket's done-time)
verifies this document against [SYS].
