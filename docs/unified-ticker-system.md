# System: the unified V-ticker (`ticker-v`) — one schedule, one steward, one record

- **About this document:** the system-stage product of ticket T-055 (project
  `hephaestus`, stage `system`, basis T-035). It specifies HOW the
  unified V-pipeline scheduler works as ONE system: how the single `ticker-v`
  schedule, the single steward session, the global spawn cap and the single
  rolling sprint record interact with each other and with the opencode service,
  and which of those interactions survive a restart or a re-arm. It fixes
  interactions and invariants — not code, not detailed design. **Read by:**
  the downstream stages deriving from T-055 (the paired test ticket verifies
  T-035's AC-2 against this document); whoever hardens `ticker-setup.ps1` or
  edits the `stage-pipeline` skill; the maintainer. **Related:**
  `docs/unified-ticker-requirements.md` (the governing definition —
  FR-001/002/003/005/007/010/011/012/015 govern here; cited below as FR-xxx),
  `.opencode/skills/stage-pipeline/ticker-setup.ps1` (the prototype arming
  script — read-only provenance; its hardening is routed in
  [Downstream routes](#downstream-routes-for-the-prototype-deltas)),
  `.opencode/skills/stage-pipeline/SKILL.md` (the one-pass tick procedure the
  steward executes), `docs/design-stage-pipeline.md` (the U-110 design — its
  ten-schedule topology is SUPERSEDED on that point, see
  [Supersession](#supersession)), `docs/opencode-v2-adoption.md` (W1: the
  `experimental.automation.*` surface this system composes (renamed from `experimental.schedule.*` in 2.3.1, #64160)), `AGENTS.md`
  (the ticket/sprint workflow this system mechanizes).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps and sprint records narrated below are historical.

## Overview

The unified ticker is not a program — it is **the task store plus three
runtime actors around it**: one `ticker-v` schedule, one steward session, and
the worker/reviewer subagents the steward spawns. The store is the only
memory; the service runtime holds everything that dies with it. Exactly one
schedule fires exactly one session, which walks the whole V in one pass,
under one cap, against one rolling sprint record. Every state that matters
between ticks lives in the store; every state that lives on the service is
recreatable by re-running one script (`ticker-setup.ps1`).

```text
        opencode service (runtime host — forgets everything on restart)
┌──────────────────────────────────────────────────────────────────────┐
│  experimental.automations (W1)          session/subagent execution   │
│  ┌────────────────────────┐            ┌──────────────────────────┐  │
│  │ automation "ticker-v"  │ every N min│ steward session "ticker-v"│  │
│  │ {sessionID, trigger,   │──prompt──▶│ (child of a repo-rooted   │  │
│  │  action, runCount,     │            │  parent; stage-pipeline   │  │
│  │  lastOutcome}          │            │  skill = the procedure)   │  │
│  └────────────────────────┘            └────────┬─────────────────┘  │
│          dies with service                      │ spawns (depth 2)   │
│                                                 ▼                    │
│                                    worker ×N / reviewer ×1-per-ticket│
│                                    (background subagents ≤ Cap)       │
└─────────────────────────────────────────────────┼────────────────────┘
                                                  │ task_* verbs only
┌─────────────────────────────────────────────────▼────────────────────┐
│  task store (.harness/tasks/hephaestus/ — the ONLY memory)           │
│  tickets · claims · basis links · artifacts · history            │
│  · ONE rolling sprint record "ticker-v" (U-108 reopen semantics)      │
└──────────────────────────────────────────────────────────────────────┘
```

## Major parts

- **The opencode service (runtime host)** — owns the `experimental.automations`
  surface (W1: list/get/create/pause/resume/delete), session execution and
  background subagents. Everything it holds is runtime state: schedules, live
  sessions, running subagents, and the steward's cap count all die with the
  service (constraint C-A: `"experimental": { "schedules": true }` must be on).
- **The `ticker-v` schedule** — one experimental-schedule record
  `{sessionID: <steward>, name: "ticker-v", when: {type: "every", every: "N minutes"},
  prompt: <tick prompt>}`, plus the service-maintained `runCount` /
  `lastOutcome` health fields. It is a per-session timer primitive (W1): it
  re-sends its prompt to its bound session. Its name is the system's identity
  key (INV-1). Arm-time parameters (C-F): `-IntervalMinutes` (default 15) and
  `-Cap` (default 4).
- **The steward session** — one session titled `ticker-v`, created as a CHILD
  of a repo-rooted parent (C-C: children inherit the parent's project context
  — the `tasks` MCP tools and the `stage-pipeline` skill; a home-rooted
  session gets user-global config only and has no task tools, observed live
  2026-11-14). It executes the tick prompt via the stage-pipeline skill: one
  pass over all ten stages in canonical order (FR-005). It holds the big
  picture and the cap counter; it never works a ticket itself and never
  accepts one.
- **Worker subagents** — background children of the steward, one per claimed
  ticket (FR-008), briefed with exactly that ticket + its direct basis
  artifacts + its stage's discipline skill. They record the artifact and set
  `in-review`; they never set `done`.
- **Reviewer subagents** — background children of the steward, one per
  in-review ticket per stage visit (FR-006 step 2), read-only, delivering the
  engine verdict (PASS → done, FAIL → blocked, UNCLEAR → one retry).
- **The task store** — the single coordination blackboard, mutated only via
  `task_*` verbs. Carries tickets, statuses, claims (assignee), basis
  links, artifact indexes, the sprint records, and the append-only history.
  The store never loses a confirmed write.
- **The rolling sprint record `ticker-v`** — ONE store-side sprint record
  reused across ticks (FR-012) under U-108 reopen semantics (below).
- **The PO / kickoff assistant** — outside the runtime loop: arms, pauses,
  resumes (one command), and resolves NEEDS-HUMAN escalations.

## The interaction model

Four interfaces carry the whole system. Each section states the interaction,
the governing FRs, and the invariant it must preserve.

### I-1 Automation × steward (`experimental.automations`)

- **Arming** = `POST /api/experimental/automation` with the steward's session
  id, `name: "ticker-v"`, the `trigger` interval, and the `action` tick prompt (exactly what the
  prototype's script does). The prompt is **arm-time data**: the canonical
  stage list and the Cap number are interpolated into it by
  `ticker-setup.ps1` — the cap reaches the steward through the prompt and
  nowhere else, so **changing Cap or cadence means re-arming** (C-F).
- **Firing**: when the interval elapses, the service delivers the tick prompt
  to the steward session as a new turn; the steward runs ONE pass over all
  ten stages (FR-002/FR-005). There is no state carried between ticks in the
  prompt or the schedule — each firing is a complete, independent pass.
- **Health** (NFR-OPER-001): the pipeline's liveness is read from ONE
  schedule record — `runCount >= 1` and `lastOutcome = fired` after an
  interval — never assembled from several records (the ten-schedule cost this
  system exists to remove).
- **Identity and exactly-one** (FR-001, FR-003): the name `ticker-v` is the
  single identity key. Arming when a `ticker-v` schedule already exists on
  the running service MUST leave exactly ONE schedule and exactly one steward
  session afterwards (reuse or replace — never duplicate). The prototype
  violates this today (it POSTs unconditionally); the required behavior and
  its mechanism (lookup-by-name reuse vs retire-and-recreate — Open Question
  Q-001) are routed to the implementation leg in
  [Route A](#route-a--implementation-leg-hardening-ticker-setupps1).
- **Lifetime**: the schedule is service-runtime state and dies with the
  service (FR-015's premise). The steward session's *record* persists in
  opencode storage across restarts, but it is inert without its schedule —
  nothing fires it. An orphaned steward (schedule deleted/paused, session
  alive) is dormant, not running.
- **Pause / disarm**: the W1 surface's pause/resume/delete are the explicit
  disarm path; the organic pause is simply shutting the machine down. Both
  converge on the same resume: FR-015's one command.

### I-2 Steward × workers/reviewers (sessions, nested depth)

- **The session tree is three levels deep**: repo-rooted parent → steward →
  worker/reviewer subagents. The steward is itself a subagent (one level);
  its spawns are the second (constraint C-B). `experimental.subagent_depth >= 2`
  must hold in the user-global config (setup-local per D-004) — without it
  the steward cannot spawn its workers and the system cannot run at all.
  This constraint stays documented as a setup-local coherence requirement —
  routed in [Route C](#route-c--coherence-note-subagent_depth).
- **Worker lifetime**: a background spawn returns immediately and the worker
  reports on idle. Its entire store footprint is its own ticket:
  `task_add_artifact`, `task_update` → `in-review`, `task_add_comment`,
  `task_set_blocked` when stuck. It never touches another ticket and never
  sets `done` (the verdict is the engine's).
- **Reviewer lifetime**: spawned during the pass's in-review step, read-only,
  verdict applied, then idle.
- **Worker death**: a worker that dies mid-flight (service shutdown, crash)
  leaves its claim behind in the store — the store truthfully reports
  `in-progress` with the claim's assignee, but nothing is executing it. The
  store cannot distinguish a dead claim from a slow one; reconciliation is a
  steward duty at the next tick ([Restart scenarios](#restart-and-re-arm-scenarios)).
- **Lane serialization** (FR-009): the steward never runs two subagents whose
  file lanes overlap; overlapping claimables spawn serially within the pass.

### I-3 Steward × store (the only memory)

- **Read path** (the tick's observation): `task_readiness` per stage — the
  empty-V cost is exactly one readiness query per stage, a successful pass
  (FR-014); plus `task_get` / `task_list` / `task_board` as needed for
  verdicts and division.
- **Write path**, in the tick's fixed order (FR-006): escalation
  (`task_set_blocked` on the *basis* ticket) → verdicts (`task_update` →
  `done` by the reviewer's PASS only; FAIL → `task_set_blocked`) → division
  (`task_create` with `stage`/`basis`, artifacts carried) → kickoff
  (`task_plan_sprint` THEN `task_claim` THEN spawn — FR-007's plan-then-claim
  is an ordering invariant: claims take `sprint-backlog` tickets only, and
  division-born tickets start in `product-backlog`; planning without planning
  first would make them unclaimable) → pass close (`task_close_sprint`).
- **One attempt per ticket per tick** — no hot loops; a UNCLEAR verdict
  round-trips once, then waits for the next tick.
- **Mid-pass store loss** (observed live, T-045): the tasks MCP namespace can
  drop mid-run (opencode cycles the tasks server on write bursts / session
  spawns). System behavior: reconnect
  (`POST /api/experimental/mcp/tasks/connect?location[directory]=<repo>`,
  URL + token from `service.json`) and CONTINUE the pass where it stopped.
  The store never loses a confirmed write; an aborted pass is the last
  resort. All invariants are phrased so that confirmed writes alone suffice
  to restore coherence.

### I-4 The global spawn cap

- **Where it lives**: in the steward's decision procedure (the tick prompt +
  the skill's boundaries section). No surface enforces it. The cap is global
  *by construction* because exactly one steward exists (FR-001) — one counter
  owner, not the sum of ten (US-002).
- **Count-before-spawn** (FR-010): before EVERY spawn the steward counts its
  own live children — **both workers and reviewer subagents count**: the cost
  bound (NFR-COST-001) is about concurrent subagent tokens, and reviewers are
  the steward's children like any worker. A child stops counting when it has
  delivered its completion (worker: artifact recorded + `in-review` set +
  idle; reviewer: verdict applied + idle) — the *ticket* it touched may still
  be `in-progress`/`in-review` in the store, but that is store state, not a
  running subagent.
- **Defer-at-cap** (FR-011): a spawn that would exceed Cap is deferred, never
  exceeded. The claimable ticket is **planned into the rolling record but not
  claimed** — it stays `sprint-backlog` and the next tick claims it first
  (claim implies spawn; queuing an already-claimed ticket would break that
  pairing).
- **Cap value**: arm-time (default 4), delivered inside the tick prompt
  (I-1). It is steward-instance-local: the counter lives in the running
  steward's context and dies with it — see the re-arm hazard in
  [Scenario S-3](#s-3--re-arm-while-armed-no-restart).

### I-5 The ONE rolling sprint record (`ticker-v`)

- **One record for the whole pipeline** (FR-012): reused across ticks, not a
  record per tick and not a record per stage (this supersedes the
  `ticker-<stage>` per-stage records of the previous topology). One id keeps
  the sprint namespace compact; the digest history stays in the record's
  goal/digest updates and the tickets' own histories.
- **Reopen semantics (U-108)**: `task_plan_sprint` into a CLOSED `ticker-v`
  record reopens it (status `active`, `closedAt: null`, `createdAt`
  preserved); planning into an ACTIVE record is a goal update only. So the
  per-tick lifecycle is simply: plan (reopens if closed) → claim → spawn → …
  → close at pass end.
- **In-flight protection (FR-013)**: `task_close_sprint` ejects unfinished
  tickets to `product-backlog` — closing while any ticket is claimed
  (`in-progress`) or otherwise in flight corrupts fulfillment (the claim's
  sprint membership evaporates mid-run). Therefore the record closes ONLY at
  a pass end where nothing is claimed or in flight; when work is in flight
  the record stays ACTIVE across ticks and a later tick closes it with the
  digest (the skill's field observation, now a system rule).
- **The record is store state** — unlike the schedule it survives restarts
  and re-arms untouched ([Remember vs forget](#what-the-store-remembers-vs-what-the-service-forgets)).

## The tick as a system event

One firing of the schedule = one pass (FR-005): for each stage in canonical
order — requirements, system, architecture, design, implementation,
test-implementation, test-design, test-architecture, test-system,
test-requirements — the steward processes exactly that stage's tickets in the
fixed order (FR-006): BLOCKED → escalate to basis; IN-REVIEW → verdict via
one reviewer subagent; DONE-without-children → divide; then once per pass:
READY → plan-then-claim under the cap → spawn one worker per claimed ticket
(FR-007/008); close the pass with a digest (only when I-5's in-flight
protection allows). An empty V is a successful pass costing one readiness
query per stage (FR-014). The tick boundary is the only synchronization
point; stages never call each other — division writes the links, the next
pass's readiness scan discovers them.

## Invariants

| # | Invariant | Anchor | Preserved by |
|---|---|---|---|
| INV-1 | Exactly ONE `ticker-v` schedule exists on the running service | FR-001, FR-003 | the re-arm dedupe-or-retire guard (Route A); no per-stage arming path (FR-004) |
| INV-2 | Exactly ONE steward session drives it | FR-001 | the same re-arm guard; steward reuse-vs-recreate decided at design (Q-002) |
| INV-3 | At most Cap concurrent steward children, across the whole pass | FR-010, FR-011 | count-before-spawn + defer-at-cap (I-4); re-arm drain-or-reuse (Route A, S-3) |
| INV-4 | Plan-then-claim: no claim without a prior `task_plan_sprint` into `ticker-v` | FR-007 | the tick's fixed write order (I-3) |
| INV-5 | ONE rolling record `ticker-v`; never closed while claimed/in-flight | FR-012, FR-013 | U-108 reopen lifecycle + in-flight protection (I-5) |
| INV-6 | The store is the only memory; resume = re-running `ticker-setup.ps1` alone | FR-015, NFR-REL-001 | remember-vs-forget split (below); the one-command resume |
| INV-7 | The ten per-stage schedules stay retired — no path recreates them | FR-004 | the unified script is the only arming path (Route A removes the duplicate hazard) |

## What the store remembers vs what the service forgets

| | Remembered by the store (survives everything) | Held by the service (dies on restart) |
|---|---|---|
| Work items | every ticket: status, stage, basis links, assignee (claims), artifacts, labels, full history | — |
| Sprint | the ONE `ticker-v` record: active/closed, membership, goal, createdAt (U-108 reopen) | — |
| Execution | — | the schedule (binding, interval, prompt), `runCount`/`lastOutcome` |
| Sessions | — | live steward + worker/reviewer processes (session *records* persist but are inert without their schedule) |
| Cap | — | the steward's running-children count (steward-instance-local) |
| Config | — | the `tasks` MCP connection (may need reconnect after a deploy replaces the jar) |

The split is total by design (NFR-REL-001): a shutdown loses nothing that
cannot be re-derived, because every decision the next tick makes is made from
store state alone.

## Restart and re-arm scenarios

The scenario walk is the coherence proof for T-035's AC-2/AC-4 and T-055's
third criterion: the invariants survive each scenario **at the system level**.

### S-1 — steady-state tick

Schedule fires → steward runs the pass (I-1) → per-stage visits (FR-006) →
plan-then-claim under the cap (I-3/I-4) → workers spawn, land artifacts, set
`in-review` (I-2) → record closes with digest when nothing is in flight
(I-5). All invariants hold trivially.

### S-2 — shutdown mid-pass (the pause case)

The service dies while workers run. **Lost**: the schedule, the steward, the
workers, the cap count. **Survived**: every store write already confirmed —
claims, `in-review` hand-offs, recorded artifacts, the sprint record (active,
because work was in flight — I-5 never closed it). On resume (re-run
`ticker-setup.ps1`), the steward's first pass **reconciles from store state
alone**:

1. `in-review` tickets → normal verdict step (the work landed; reviewers judge
   it on ticket + artifacts, unaware it was ever interrupted).
2. Claimed (`in-progress`) tickets whose fulfiller no longer exists →
   **orphaned claims**: the steward releases them (`task_release` →
   `sprint-backlog`) and re-claims + re-spawns them under the cap. Detection
   of "dead claim" (bounded staleness vs session-existence probe) is a
   design-stage decision; the system contract is only: an orphaned claim is
   *released and re-queued*, never silently left `in-progress` forever, and
   never double-spawned while its worker might still be alive.
3. The rolling record stays active until step 1–2 have drained (in-flight
   protection applies to interrupted work exactly as to live work).

Nothing else needs repair: division-born tickets already in the store are
found by readiness; the retired per-stage schedules stay retired.

### S-3 — re-arm while armed (no restart)

`ticker-setup.ps1` runs on a service that already has `ticker-v`. REQUIRED
(FR-003/AC-004): exactly ONE schedule and ONE steward afterwards. The
prototype today duplicates instead (requirements-delta #1) — routed to
Route A. System-level hazard the guard must handle: the cap counter is
**steward-instance-local** (I-4). If re-arm REPLACES the steward while the
old steward's children are still running, the new steward starts counting at
zero and INV-3 can be silently exceeded (old children + new spawns > Cap).
The re-arm mechanism must therefore either REUSE the steward session (count
preserved; Q-002's reuse option) or ensure the old steward's children drain
before/while the new one spawns (Q-002's fresh option, with a drain rule).
Route A carries this constraint explicitly.

### S-4 — re-arm after restart (the resume case)

FR-015: resume = re-running `ticker-setup.ps1` alone. The store remembers
everything in the table above; the first pass reconciles exactly as in S-2
step 1–3. No ticket, sprint, link or artifact state needs re-arming. The
exactly-one outcome of the re-arm itself is FR-003 (S-3's guard).

### S-5 — cap-starved pass

More READY tickets than the cap allows: the steward plans all of them into
`ticker-v`, claims and spawns only up to Cap, defers the rest unclaimed
(`sprint-backlog`) — never exceeding (FR-010/FR-011). The next tick claims
the deferred tickets first. Workers from earlier ticks count while alive, so
a pass starting with live carry-over workers has correspondingly fewer free
slots — that is the cap being global across ticks, not just within a pass.

### S-6 — in-flight at pass boundary

The pass reaches its end while any ticket is claimed/in flight: the record
stays ACTIVE (FR-013) and closes on a later pass with the digest. Closing is
gated, never forced.

## Downstream routes for the prototype deltas

The requirements stage honestly recorded four prototype deltas
(`docs/unified-ticker-requirements.md`, §Prototype deltas). Per the T-035
reviewer note (N1) and T-055's scope, they route as follows. **This ticket
specifies the routes; it does not create the downstream tickets** — division
at done-time mints them per the linked stage pipeline — and it does not edit
the prototype files.

### Route A — implementation-leg hardening (`ticker-setup.ps1`)

- **Owning stage/leg:** the implementation stage of the ticker-v arc (role
  developer; file lane: `.opencode/skills/stage-pipeline/ticker-setup.ps1`
  only).
- **Content (both deltas ride one ticket — same file, same behavior):**
  1. **Re-arm dedupe-or-retire guard** — delta #1, required by FR-003/AC-004
     and invariants INV-1/INV-2. On invocation, look up the existing
     `ticker-v` schedule by name (the W1 surface has list/get) and REUSE
     (update interval/prompt) or retire-and-recreate — never POST
     unconditionally. Decide Q-001 (mechanism) against the actual API
     surface, and Q-002 (steward reuse vs fresh) with S-3's cap-drain
     constraint: fresh-steward recreation must not let old children blow the
     cap.
  2. **`$Password` → `$Token` param fix** — delta #3: line 25 tests
     `$Password`, which is never defined (the parameter is `$Token`), so the
     fallback branch is always taken and explicit `-BaseUrl`/`-Token`
     overrides are clobbered by `service.json`. Fix the condition to test
     `$Token`.
- **Verification pairing:** the paired `test-*` ticket for the implementation
  leg proves AC-004 (re-run yields exactly one schedule) and AC-005 (the ten
  per-stage schedules stay retired).

### Route B — skill lane (`SKILL.md`)

- **Owning stage/leg:** a skill-lane ticket (role developer; file lane:
  `.opencode/skills/stage-pipeline/SKILL.md` only).
- **Content:** delta #2 — the pause/resume field observation still says
  "re-run `ticker-setup.ps1` with the same `-Stages`", a per-stage-era
  leftover; the unified script has no `-Stages` parameter. Replace with the
  unified truth: resume = re-run `ticker-setup.ps1` (same interval/cap
  choices if desired — C-F), store is the memory. This document's
  [I-1](#i-1-automation--steward-experimentalautomations) and S-4 are the
  governing text to reconcile to.

### Route C — coherence note (`subagent_depth`)

- **Owning surface:** the lanes that own the configuration/coherence
  documentation (user-global config guidance per D-004; the AGENTS.md ticker
  sections at their next coherence pass) — a note, not a code change and not
  a file lane of this arc.
- **Content:** delta #4's constraint half — `experimental.subagent_depth >= 2`
  must stay documented wherever the unified ticker is described (C-B, setup-local
  per D-004): the steward is one subagent level, its workers the second;
  without it the ticker cannot spawn fulfillment workers at all (I-2). The
  prose-coherence half of delta #4 (AGENTS.md / design / per-stage
  requirements still describing the ten-schedule strategy) already belongs to
  the queued coherence lanes that own those files — this document and the
  requirements doc are the amendment's content.

## Technology choices

| Concern | Choice | Why |
|---|---|---|
| Scheduling | opencode `experimental.automations` (W1; renamed from schedules in 2.3.1, #64160), one record named `ticker-v` | native per-session timer; exactly-one is one name to look up |
| Steward | one background-driven session, child of a repo-rooted parent | config inheritance (C-C); the skill is the procedure |
| Workers/reviewers | native background subagents (2.3.0 semantics) | goal-driven, idle-reported; the cap is the only admission knob |
| Cap | steward-local count-before-spawn, arm-time value in the prompt | no surface enforces it; one steward makes it global by construction |
| Memory | the task store only | resume is one command (FR-015); the service forgets by design |
| Sprint | ONE rolling record `ticker-v`, U-108 reopen | plan-then-claim needs sprint membership; compact namespace |
| Auth/transport | `service.json` url + token, Basic auth (same as the Eclipse host) | one discovery path, no new secrets |

## Open questions carried forward

- **Q-001 (idempotency mechanism)** — reuse vs retire-and-recreate by name:
  decided in the design/implementation leg against the actual schedule API
  surface. Rides Route A.
- **Q-002 (steward-session accumulation)** — reuse (history persists, cap
  count survives) vs fresh (deterministic context, needs a drain rule):
  decided at design; constrained by S-3. Rides Route A.
- **Q-003 (one-tick context fit at deep-V scale)** — the system's position: a
  pass is stateless between ticks and the store carries all state, so the
  *contract* never depends on one pass fitting the window; if it ever stops
  fitting, the fallback shape is a store-carried stage cursor and per-stage
  sub-passes — a design decision, flagged for the paired test (T-035 AC-2).
  No mechanism is specified here.
- **Q-004 (cadence/cap defaults)** — one ticker walking all ten stages may
  want a longer interval than 15 min; owner call at arm time (C-F). Changing
  either value = re-arm (I-1).

## Traceability

Governing FRs from `docs/unified-ticker-requirements.md`, and where this
document specifies them:

| FR | Stated here |
|---|---|
| FR-001 (one schedule + one steward, named `ticker-v`) | I-1, INV-1/INV-2 |
| FR-002 (the tick prompt drives the pass) | I-1 (arming/firing) |
| FR-003 (exactly-one on re-arm) | I-1, S-3, Route A |
| FR-005 (canonical ten-stage order) | The tick as a system event |
| FR-007 (plan-then-claim) | I-3, I-4, INV-4 |
| FR-010/FR-011 (global cap, defer at cap) | I-4, INV-3, S-5 |
| FR-012/FR-013 (one rolling record, in-flight protection) | I-5, INV-5, S-6 |
| FR-015 (resume = one command) | I-1, S-2/S-4, INV-6, remember-vs-forget |
| Supporting: FR-004/006/008/009/013/014 | FR-004 → INV-7; FR-006 → the tick section; FR-008/009 → I-2; FR-014 → I-3 |

Ticket-level acceptance criteria for T-055: the system model documented
coherently citing the FRs (→ whole document); each prototype delta routed
(→ Routes A/B/C); the one-schedule/one-session/one-record invariants survive
restart and re-arm scenarios (→ Invariants + S-1..S-6); artifact recorded
before in-review (→ store). Upstream T-035 AC-2 ("system/design verification
of the ticker's interaction with sessions, schedules, the cap and the rolling
record") is satisfied at the system level by this document; the paired
`test-*` ticket of this arc verifies it.

## Supersession

`docs/design-stage-pipeline.md` described the ten-schedule era: its §1 model
and §4 cadence/rolling-record topology (`ticker-<stage>` records, per-stage
intervals, per-stage steward sessions) are **superseded on the ten→one point**
by `docs/unified-ticker-requirements.md` and this document. What carries over
unchanged from it: the store contract (§2 ticket contract), the link contract
(§3 basis only), readiness/STALE semantics, division and the engine verdict
(§8), fulfillment narrow-brief rules (§5), and the native-first inventory
(§11). `docs/requirements-stage-pipeline.md` is amended on exactly its
ticker-topology aspect by the requirements document. The repo-level coherence
pass for those two files belongs to the coherence lanes that own them
(delta #4) — not to this ticket's lane.
