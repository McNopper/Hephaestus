# Architecture: the autonomy-spectrum flow — intake, record, pump, gates (U-113)

- **About this document:** the architecture-level definition of the
  autonomy spectrum as a FLOW — the internal structure fixed by its basis
  `docs/autonomy-spectrum-system.md` (ticket T-101, done 2026-11-15 by
  engine review verdict). It assigns every system part to exactly one
  primary component, fixes the dependency rules as **default-deny edges**
  (the store is the only shared substrate; intake, pump and gates never
  link directly), names the interfaces where the components meet this
  repo's real machinery, and traces each of the flow's four guarantees —
  G-1 store-verifiable run position, G-2 the T-041 release floor, G-3 the
  T-039 non-blocking recurrence, G-4 the F-4 safe-end defaults — through
  the components to its evidence. **Read by:** the product-owner agent
  (the intake component), the stage tickers and the steward session (the
  pump), the engine review pass, the paired test-architecture ticket
  (T-114), the maintainer. **Related:** `docs/autonomy-spectrum-system.md`
  (the upstream basis — the parts, interfaces and data flows this
  architecture structures; cited as [SYS]), `docs/acceptance-run-architecture.md`
  (the acceptance-run architecture this flow reuses and parameterizes —
  same store, same tickers, same verdicts; cited as [AR]),
  `docs/design-stage-pipeline.md` §4 (the unchanged tick algorithm; cited
  as [DSG]), `AGENTS.md` "The autonomy spectrum (the operating model,
  owner requirement 2026-11-15)" (the doctrine; cited as [AGENTS]),
  `ROADMAP.md` Vision/Product Goal + standing rules, `.opencode/agent/product-owner.md`
  "The two dials (goal intake)" (the intake procedure; cited as [PO]);
  tickets T-113 (this stage) and T-114 (the paired verification of this
  doc).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps and sprint records narrated below are historical.

## Scope

The autonomy spectrum is not a program and adds no machinery — it is a
**run position recorded on a GOAL-root ticket at goal intake and
read back by the unchanged pump** [SYS §Overview]. Its architecture is a
**process/agent architecture** like the acceptance run's: the components
are components of the run, each anchored to a real surface of this repo
(an agent definition, a store file, a schedule config, a skill), not to a
package or library lane. The architecture question this doc answers is:
*which component owns which responsibility, who may write to whom through
which interface, and through which component chain each guarantee's
evidence flows* — so that a reviewer reading only the store can verify a
run's position, its stop conditions and its release state. Where [AR]
already fixes structure (the store substrate, the tick control, the
engine verdict), this doc records the REUSE as a mapping and adds only
what the spectrum itself contributes: the intake actor, the dial record
as data, the doctrine/parameter layer, and the release boundary.

## Chosen pattern

Evaluated against the system definition: a dedicated "spectrum engine" or
dial-service module would add machinery the basis explicitly forbids
("the dials tune stop conditions and human-entry points; they add no
machinery" [SYS §Overview]); a layered pattern would imply call stacks
between intake, pump and gates that the flow forbids — they never link
directly [SYS §Overview; this ticket's named rule].

Chosen: the acceptance run's **blackboard over the task store, driven by
a fixed control cadence** [AR §Chosen pattern], extended by exactly two
things: an **intake actor** that writes the run position, and the
position as **advisory kickoff parameter data** on the blackboard. The
independent actors around the shared substrate are the ones [AR] already
names (ticker, worker, verdict) plus the intake actor; the control loop
per stage is the unchanged tick algorithm [DSG §4]. This is the pattern
the basis describes; the architecture's job is to name the components,
make the edges explicit and default-deny, and pin the guarantees to the
edge set rather than to convention.

## Components and layers

| Component | Layer | Responsibility (from the basis) | Anchored in (real code) | Basis |
|---|---|---|---|---|
| **C1 `intake`** | actor (intake) | The single point of entry for a run's position: classifies the owner's objective as a GOAL root (`stage: requirements`, never stageless); asks about the two dials (goal horizon, review cadence) when the owner leaves them unstated — bounded round-trips, then registers with the open question recorded; writes the labeled dial record onto the GOAL ticket; cites the ticket id back. Never steers the pump, never verdicts, never releases. | `.opencode/agent/product-owner.md` "The two dials (goal intake)" (U-105 lineage, recorded by T-042; write-denied for repo files) | [SYS §Major Parts 1; §F-1; PO] |
| **C2 `store`** | substrate | The single coordination blackboard — the same substrate as [AR] M1: tickets with `basis` (derivation) links, readiness, append-only history. Owns the run position as DATA: the dial record is labeled text on the GOAL-root ticket file — the position's ONLY home; child tickets inherit via their `basis` chain, never restated. Text custody only: no dial schema, no dial validation, no dial-driven store behavior. | `.harness/tasks/hephaestus/<GOAL-id>.md` + the `task_*` verb surface; git-versioned Markdown (D-006) | [SYS §Major Parts 2; §Interfaces 1/3] |
| **C3 `pump`** | control | The stage tickers — the same control layer as [AR] M2: the tick algorithm is IDENTICAL under both horizon modes [DSG §4]. The only dial input is at kickoff: read the goal root's horizon to decide whether launching continues (run-to-Product-Goal = schedules recur; time-boxed = the steward disarms at the horizon). The cadence dial has no edge into the pump at all. The schedule lane: the steward arms the stage schedules at kickoff and disarms them at a time-boxed horizon; in-flight tickets always run to their engine verdict — never abandoned mid-flight. | One steward session per stage on `experimental.automations`, armed/disarmed by `.opencode/skills/stage-pipeline/ticker-setup.ps1`; the `stage-pipeline` skill | [SYS §Major Parts 3; §F-2; DSG §4] |
| **C4 `gates`** | governance | The engine review pass plus the human review — [AR] M4 extended with the release boundary: engine verdicts gate every ticket's `done` (unchanged, [AR] AD-003); the wave close always ends at commit + push; a HUMAN review that accepts is the only release trigger; without one the release stays pending. Owns the acceptance-recurrence verdict lane (the fresh test-requirements acceptance ticket's eventual review). | The engine review pass + the project quality gate; `eclipse/releng/release.ps1` (tag per D-002; the `-SNAPSHOT` bump on demand, `-BumpNext`) reachable only from the human-review context | [SYS §Major Parts 4; §F-3] |
| **C5 `doctrine`** | knowledge (static) | The standing rules and procedures the actors load — data, not an active process: the two-dial vocabulary and the safe-end defaults, the T-041 standing release rule and the T-039 recurrence rule, the tick algorithm, and the division-map entry that carries the acceptance recurrence. Process change = editing the doctrine once; every run inherits it. | `AGENTS.md` "The autonomy spectrum (the operating model, owner requirement 2026-11-15)"; `ROADMAP.md` Vision/Product Goal + standing rules; [PO]; [DSG §4] | [SYS §Technology choices; §The two dials] |

```text
[C5 doctrine] (static knowledge: AGENTS.md spectrum rules · T-041/T-039
   ▲    ▲       standing rules · PO two-dials procedure · DSG §4 tick)
   │E4  │E5
   │    └──────────────┐
[C3 pump]          [C1 intake]
   │  │                │
   │  │ E2 read the    │ E1 writes the dial
   │  │ goal root +    │ record onto the GOAL
   │  │ tick verbs      │ root at intake
   │  ▼                ▼
   │ ┌────────────────────────────────────────────┐
   │ │ [C2 store]  tickets · basis links ·   │
   │ │   readiness · history · THE DIAL RECORD    │
   │ │   (labeled text on the GOAL root)     │
   │ └────────────────────────────────────────────┘
   │                    ▲ E3 reads + done
   │ E6 arm/disarm      │
   ▼                [C4 gates]
[stage schedules]       │
(armed at kickoff,      └─ human review that accepts ──► [release]
 steward disarms at       (engine verdicts)        (no machine component
 the time-boxed horizon)                             holds this edge — T-041)
```

### System part → component mapping

Every system part from [SYS §Major Parts] maps to **exactly one primary
component**; cross-cutting concerns are noted without new owners:

| System part (basis) | Primary component | Note |
|---|---|---|
| Goal intake — the product-owner agent | **C1** | the only new actor the spectrum contributes |
| The store record — the GOAL-root ticket | **C2** | the dial record is C2 data, not an actor |
| The pump — stage tickers on schedules | **C3** | unchanged tick algorithm; dial input at kickoff only |
| The review/acceptance gates | **C4** | [AR] M4 + the release boundary; the human review is the trigger |

Cross-cutting (each anchored in an existing component):

- **The dial record's shape** — a C5 convention (the record format in
  [PO] and [AGENTS]), with C2 giving custody only; written only by C1
  (E1), read only at kickoff by C3 (E2). Nothing else may restate,
  transform or re-home it.
- **The acceptance recurrence** — SP-4's rule, but carried as DATA: the
  C5 division map's recurrence entry ([AGENTS]: "the stage-pipeline
  skill's division map carries the recurrence"), landing in C2 at the
  wave-close boundary. Whichever actor's division map fires it, the write
  goes through the store — the architecture fixes the channel and the
  non-blocking property, not the firing hand. This keeps pump invariance:
  the recurrence is data C3 already loads (E4), not a new pump behavior.
- **NEEDS-HUMAN escalation** — C3's BLOCKED step through C2
  (`task_set_blocked` onto the basis; NEEDS-HUMAN as the last resort
  after agents had their attempt) [SYS §Interfaces 5; DSG §4]. The
  escalation threshold at every dial setting.
- **Horizon enforcement** — the C3 schedule lane (steward arm/disarm),
  not an engine concept; the engine has no horizon notion and none is
  added [SYS §Key trade-offs].

## Dependency rules (default-deny)

Layer rule: dependencies point **toward the substrate or static
knowledge only** — actors depend on substrate + knowledge; governance
depends on substrate; substrate and knowledge depend on nothing.
**Default deny: an edge exists only if listed here.**

| Edge | Direction | Interface (verbs / mechanism) | Basis |
|---|---|---|---|
| E1 | C1 → C2 | `task_create` (the GOAL root), `task_update` (the labeled dial record onto the ticket), `task_add_comment` (the intake Q&A); the write happens even when a dial answer is missing — the open question is recorded with the registration | [SYS §Interfaces 1; §F-1; §F-4] |
| E2 | C3 → C2 | Kickoff read: `task_get`/`task_board` on the goal root (the horizon) + `task_readiness` (the READY set) — children are found through the `basis` chain; tick verbs: `task_plan_sprint`, `task_claim`, division/link creation (executing the C5 division map), `task_close_sprint`; `task_set_blocked` escalation of a blocked ticket onto its basis | [SYS §Interfaces 2; DSG §4] |
| E3 | C4 → C2 | `task_get`/`task_traceability` reads; the `done` transition (engine-only, [AR] AD-003); the acceptance-recurrence ticket landing in C2 at the wave-close boundary | [SYS §Interfaces 1; §F-3] |
| E4 | C3 → C5 | Loads the `stage-pipeline` skill: the fixed tick procedure + the division map (the T-039 recurrence entry is data here) | [DSG §4; SYS §Major Parts 3; §F-3] |
| E5 | C1 → C5 | Loads the two-dials procedure: the dial vocabulary, the bounded round-trip bound, the safe-end defaults | [SYS §Major Parts 1; PO] |
| E6 | C3 → stage schedules | The schedule lane: armed at kickoff, disarmed at a time-boxed horizon (`ticker-setup.ps1`, `experimental.automations`; renamed from schedules in 2.3.1); not a store edge — schedule config is the horizon's enforcement point | [SYS §F-2; §Key trade-offs] |

Denied edges (each with its structural reason — architecture rules, not
conventions):

- **C1 → C3**: the intake actor never steers the pump — no launch, no
  stop signal, no mid-run message. The position travels only through the
  C2 record and is read only at kickoff [SYS §Overview "read back by the
  unchanged pump"; F-1's evidence is store evidence, not intake's word].
- **C1 → C4**: intake never verdicts and never releases; it writes a
  record, nothing else.
- **C3 ↔ C4 (both directions)**: the ticker never accepts (`done` is
  engine-only) and the verdict never commands the pump — the verdict
  steers only through C2 state (division triggers, readiness flips)
  [AR §Dependency rules denied edges; DSG §4].
- **C2 → anything**: the store is a passive substrate — no callbacks, no
  notifications; discovery is by read at the tick boundary [AR M1 rule].
- **Any machine component → the release trigger**: `release.ps1` is
  reachable only from the human-review context. This IS the T-041 floor
  as structure: no dial setting, no component, no script path inside the
  machine can cut a release [SYS §The two dials (dial floor); §F-3].
- **Any component → C2 files directly**: write discipline — every store
  write goes through the `task_*` verbs; hand edits are not an
  architecture edge (only the smallest documented correction, verified
  with `task_get` + `task_doctor`, recorded as a ticket comment)
  [AGENTS.md write discipline].
- **Any side channel carrying the run position**: chat memory, session
  state, a config file, a restatement on a child ticket — all denied; the
  record's ONLY home is the GOAL-root ticket file [SYS §Major Parts
  2 "never restated per child, never held in chat memory or a session"].

Direction consistency: C2 and C5 have **zero outgoing edges**; every
actor's store edge points only toward C2. The graph is acyclic by
construction — no edge ever points from the substrate or the knowledge
layer outward. The ticket's named rule holds structurally: there is no
edge between any pair of C1, C3, C4 — intake, pump and gates never link
directly; every interaction is store-mediated.

## Interfaces to the real code

Where each component meets this repo's actual machinery:

- **C1 `intake`** — `.opencode/agent/product-owner.md`: the intake seat
  is write-denied for repo files and holds only the store verbs (E1); the
  two-dials procedure fixes the ask-then-default behavior and the
  round-trip bound [PO].
- **C2 `store`** — `.harness/tasks/hephaestus/` (ticket files,
  `_meta.json`) and the `task_*` verbs served as MCP tools (the `tasks`
  stdio launcher or the `eclipse-build` endpoint; wire names stay
  `task_*`). Custody invariants: git-versioned Markdown (D-006) —
  `task_get` verifies the record, `git log` on the ticket file audits its
  provenance [SYS §Interfaces 3].
- **C3 `pump`** — one `experimental.automations` automation per stage
  (dormant when OFF), armed/disarmed by `ticker-setup.ps1`; the steward
  session carries a time-boxed horizon from kickoff and disarms at it;
  the tick is [DSG §4] verbatim — SCAN → BLOCKED → DONE → READY → CLOSE.
- **C4 `gates`** — the engine review pass (reads ticket + artifacts +
  history; verdicts on store evidence), the project quality gate on the
  shared tree, and the release machinery: `eclipse/releng/release.ps1`
  (tag per D-002; the `-SNAPSHOT` bump on demand, `-BumpNext`), invoked only when a human review
  accepts.
- **C5 `doctrine`** — the document surfaces themselves ([AGENTS],
  ROADMAP standing rules, [PO], [DSG §4]); skills and doctrine are the
  carriage of process change — edit once, every run inherits.

## Guarantee flows through the architecture

Each guarantee from the basis traced through the component chain to its
evidence — one-to-one with [SYS §End-to-end data flows]:

| Guarantee | Basis flow | Component chain | Structural evidence |
|---|---|---|---|
| **G-1** Store-verifiable run position | F-1 | C1 → E1 → C2 (the record on the GOAL root) → `basis` chain inheritance → C3 kickoff read (E2) | `task_get` on the GOAL ticket shows the labeled record; `git log` on the ticket file shows provenance; child tickets carry no restatement |
| **G-2** T-041 release floor | F-3 | C3 wave close (commit + push always) → C4 engine verdicts → human review that accepts → release | no machine edge to `release.ps1` (the denied-edge rule); without a human acceptance the release stays pending — at every cadence setting |
| **G-3** T-039 non-blocking recurrence | F-3 | C5 division-map entry (data) → wave-close landing in C2 → fresh test-requirements acceptance ticket | the ticket's link structure: nothing's `basis` or readiness references it — no component waits on it |
| **G-4** F-4 safe-end defaults | F-4 | C1 bounded round-trips → register with the open question recorded (E1) → C3 kickoff read → C5 defaults apply | no readiness, claim, verdict, wave close or commit has a dial input anywhere — no dial state can block a wave |

- **G-1 Store-verifiable run position.** C1 writes the labeled dial
  record onto the GOAL-root ticket through E1; C2 holds it as the
  ticket file's text (git-versioned, D-006); children inherit through
  C2's `basis` chain, so every stage ticker of the run reads the same
  position without per-ticket restatement (E2). The structural guarantee
  is custody: the record has exactly one home and no side channel may
  carry it, so verification always resolves to the store — `task_get` to
  read it, `git log` to audit who wrote it and when [SYS §Major Parts 2;
  §F-1].
- **G-2 T-041 release floor.** A completed wave always ends at commit +
  push (C3's wave-close boundary, worker lanes landed); every ticket's
  `done` is C4's engine verdict (E3), unreachable for C1/C3; the release
  trigger has no machine edge at all — only a human review that accepts
  reaches `release.ps1`. The floor therefore does not depend on any
  actor's good behavior: it IS the edge set. A tighter cadence setting
  adds human attention; it cannot subtract the human from the release
  [SYS §The two dials (dial floor); §F-3].
- **G-3 T-039 non-blocking recurrence.** The recurrence rule lives in C5
  as a division-map entry — data the pump already loads (E4), so the tick
  algorithm stays unchanged — and lands as a fresh test-requirements
  acceptance ticket in C2 at the wave-close boundary, one per successful
  wave/release at every cadence setting. Non-blocking BY STRUCTURE: the
  ticket has no downstream that anything waits on — no `basis` depends on
  it, no readiness references it — so acceptance hardens without gating
  [SYS §Major Parts 4; §F-3].
- **G-4 F-4 safe-end defaults.** An unanswered dial question does not
  hold the goal: C1 asks with bounded round-trips and then registers with
  the open question recorded (E1 fires regardless); C3's kickoff read of
  an absent or unresolved record falls through to C5's defaults — the
  run-to-Product-Goal horizon (the general rule) with the T-041 cadence
  floor. The structural guarantee is that dial state guards nothing: no
  component's claim, verdict, wave close or commit path consumes a dial
  value as a precondition, so the position can steer a run but can never
  stall one [SYS §F-4].

## Key quality attributes

- **QA-1 Non-blocking** — the dials never stall a wave [SYS §Overview
  "they never stall a wave"; §F-4]. Structure: the position is advisory
  kickoff data (E2) with doctrine defaults behind it (E5); no gate, no
  readiness input and no claim dependency touches dial state; the only
  unplanned stop a run takes is NEEDS-HUMAN.
- **QA-2 Verifiability** — the run position is store-verifiable and
  git-auditable, never memory-resident [SYS §Interfaces 3]. Structure:
  single-home custody in C2 plus the denied side channels; D-006
  versioning makes provenance `git log`-decidable.
- **QA-3 Pump invariance** — the tick algorithm is identical under both
  horizon modes, and the cadence dial has no pump edge at all [DSG §4;
  SYS §Major Parts 3]. Structure: the only dial inputs are the kickoff
  stop-condition read (E2) and the schedule lane (E6); C3's procedure is
  otherwise exactly [AR] M2's.
- **QA-4 Safety floor** — releases-only-on-human-review at every setting;
  NEEDS-HUMAN the escalation threshold [SYS §The two dials; §F-3]. The
  release floor as structure (QA-4 via G-2) plus C3's BLOCKED escalation
  through C2 onto the basis.

## Architecture decisions

- **AD-1 Reuse over parallel structure** — C2 ≡ [AR] M1, C3 ≡ M2, C4 =
  M4 extended with the release boundary; the spectrum's only new elements
  are C1, the dial record as C2 data, and the C5 parameter layer [SYS
  §Overview "they add no machinery"]. Trade-off: two architecture docs to
  read ([AR] + this one); alternative rejected: a dedicated spectrum
  engine — new machinery the basis forbids.
- **AD-2 The store is the only shared substrate** — intake, pump and
  gates never link directly; all coordination is store-mediated (this
  ticket's named rule; [AR] AD-002 inherited). Trade-off: coordination
  latency — cross-actor interactions wait for store-mediated boundaries
  by design.
- **AD-3 The dial record is a ticket-record convention, not store
  schema** — C2 gives text custody only; C1 owns the shape. If intake
  misses recur, promoting the dials to first-class ticket fields is the
  follow-up — a C2/store change, never a C3/pump change [SYS §Key
  trade-offs]. Trade-off: the store cannot validate presence or shape;
  the F-4 default read is the safety net.
- **AD-4 Horizon enforcement lives at the schedule lane, not the
  engine** — no engine horizon concept; the steward disarms at a
  time-boxed horizon while in-flight tickets always run to their engine
  verdict — a ticket is never abandoned mid-flight because a clock ran
  out [SYS §Key trade-offs; §F-2]. Trade-off: horizon precision is
  schedule-tick-granular.
- **AD-5 The release trigger is outside the machine** — no component
  holds an edge to `release.ps1`; the human review is the only path,
  which makes T-041 structure rather than policy [SYS §The two dials;
  §F-3]. Trade-off: releases are deliberately unautomatable.
- **AD-6 Defaults are doctrine, not new policy** — the safe end
  (run-to-Product-Goal + the T-041 floor) is the [AGENTS] general rule;
  the architecture only wires the fallback read at kickoff [SYS §F-4
  "the doctrine's own defaults, not new policy"]. Trade-off: none
  identified — the defaults are inherited, not invented.

## Open questions

None at the architecture level. The one inherited open item — promoting
the dial record to first-class store schema — is conditional (only if
intake misses recur) and would change C2's schema, never the edge set
[SYS §Key trade-offs].

## Coverage

What this architecture covers, traced to its basis and around:

- **System parts** SP-1..SP-4 ([SYS §Major Parts]) → C1..C4, one primary
  component each (§System part → component mapping); C5 carries the
  static doctrine all four load.
- **The five interfaces** ([SYS §Interfaces]) → E1 (intake → store),
  E2 (pump ← store), the git-audit interface (C2 custody, D-006), the
  wave-close interface (C3 close at commit + push; release gated on the
  human review), the NEEDS-HUMAN interface (C3's `task_set_blocked`
  escalation onto the basis).
- **The four data flows** F-1..F-4 ([SYS §End-to-end data flows]) →
  G-1..G-4 (§Guarantee flows), one-to-one.
- **The technology choices** ([SYS §Technology choices]) → position home
  = C2 (ticket file, D-006); intake actor = C1 ([PO]); pump = [DSG §4]
  verbatim; release trigger = C4 + the human review; acceptance
  recurrence = C5 data landing in C2.
- **The reuse of [AR]** (AD-1) → the store/ticker/verdict anchors
  inherited unchanged; this doc adds no edge to any [AR] module and
  re-decides nothing [AR] already fixes.
- **Not covered (out of scope)**: the concrete dial VALUES of any given
  run (a goal-intake decision recorded per [PO], not an architecture
  fact); the Eclipse Board's projection of tickets (the store is the
  ground truth; the Board is a view); store schema design (C2 semantics
  live in the store contract [AR] already cites).
