# Design: the acceptance run's inside-of-modules design (T-095)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket **T-095** (epic U-111,
  basis A-003, stage `design`): the design INSIDE the acceptance run's
  five modules — per-component contracts (units and their interfaces, in
  `name(inputs) -> output | errors` form), the three key workflows (the
  tick procedure, the claim-spawn-fulfill-verdict-divide cycle, the
  shutdown-mid-pass reconciliation), and the data structures the modules
  consume (ticket schema usage, sprint record lifecycle, history event
  semantics). The module set M1–M5, the layers and the default-deny edge
  table E1–E7 are the architecture's fixed structure ([ARCH]); this doc
  adds no module and no edge.
- **Read by:** the downstream implementation stage (its basis — what gets
  built per component), the paired `test-design` stage (ticket T-096,
  whose criteria CT-D-001..CT-D-007 in
  `docs/acceptance-run-design.tests.md` decide this artifact), the engine
  review pass (the only `done` authority), and the maintainer.
- **Related:** `docs/acceptance-run-architecture.md` (A-003's product, as
  amended 2026-11-15 by T-097/T-098 — the governing definition; cited
  [ARCH]); `docs/acceptance-run-system.md` (A-001, as amended by T-097 —
  cited [SYS]); `docs/design-stage-pipeline.md` (the U-110 design — where
  it fixes structure this doc records the pass-through, §7; cited [DSG]);
  `docs/acceptance-stage-pipeline.md` (the run's requirements — the
  demonstration contract D-1/D-2/D-3; cited [REQ]);
  `docs/acceptance-run-design-amended.md` (T-111 — the sibling design of
  the 2026-11-15 amendment's delta; the two docs compose without overlap,
  §1); `docs/acceptance-run-design.tests.md` (T-096 — the criteria this
  artifact is judged by). Design decisions are **DD-01..DD-06**, data
  structures **DS-1..DS-5** (doc-local ids).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps and sprint records narrated below are historical.

## 1. Design scope

The acceptance run is a process/agent architecture: its modules are
modules of the run, anchored in store, schedule, skill and subagent
surfaces ([ARCH §Scope]). The design INSIDE each module is what this doc
fixes; the composition rule with the sibling is the one T-111 recorded:
**T-095 designs the untouched whole, T-111 designs the amendment's
delta.** Concretely:

- This doc designs every module's components, contracts, workflows and
  data structures for the machinery as it stands under the amended
  attribution model — the amendment's settled facts (partial
  caller-asserted attribution, bare status transitions, engine marks) are
  consumed exactly as [SYS §Interfaces 1] states them, cited at their
  point of use, never re-decided.
- The amendment-specific contracts (the store's history/attribution
  surface C-A1, the review-pass evidence marks C-A2, the done-flip
  evidence flow C-A3) are **T-111's products**; where this doc's
  contracts touch them it cites the sibling instead of duplicating them.
- Everything [DSG] already fixes is recorded as pass-through (§7) — the
  same discipline [ARCH §Pass-through] applies one level up.

## 2. Component contracts

Contract notation throughout: `name(inputs) -> output | errors`. Every
module carries at least one contract with all three faces (inputs,
outputs, error behavior).

### 2.1 M1 `store` — the substrate

Reality anchor: `.harness/tasks/hephaestus/` (one Markdown file per
ticket, `_meta.json`) plus the `task_*` MCP verb surface; invariants the
module guarantees: append-only history with partial actor attribution,
the done-time artifact guard, atomic claim as the only serialization
point ([ARCH §Interfaces to the real code M1]; [SYS §Interfaces 1]).

- **C-M1-1 `mutate(request) -> Ticket`** — the ticket mutation verb
  family (`task_update`, `task_set_blocked`, `task_clear_blocked`, the
  division/link creation helpers). Inputs: the verb's typed request
  (ticket id + field writes). Outputs: the rewritten ticket + one
  append-only history event per change. Errors: invariant violations are
  **refused** — a `done` on a definition-stage ticket with zero recorded
  artifacts (the done-time guard), any write to `stage:` (immutable
  ownership), a `blocked` on a done/paused ticket; unknown id →
  not-found.
- **C-M1-2 `claim(ticketId, by) -> Ticket | null`** — atomic claim
  (`task_claim`), the store's only serialization point. Inputs: ticket
  id, caller-asserted acting identity. Outputs: the ticket moved
  `sprint-backlog` → `in-progress` with `assignee` set (history: stamped
  claim event), or `null` when another actor won the race. Errors:
  contention is not an error — the loser observes `null` and skips; a
  ticket not in claimable status → refused.
- **C-M1-3 `readiness(stage) -> ReadinessMap`** (`task_readiness`) —
  per-ticket stage state: `READY / WAIT_UPSTREAM / STALE / BLOCKED /
  RUNNING / NOT_APPLICABLE` (DS-4). Inputs: stage id. Outputs: the map +
  the freshness basis (each row's upstream `inputs_changed_at` stamps).
  Errors: none — the read is total; an unparsable ticket file surfaces as
  a doctor-visible anomaly in the map, never silently skipped
  (`task_doctor` is the store's own lint surface).
- **C-M1-4 `append(event) -> HistoryEvent` / `readHistory(ticketId)`** —
  the append-only history (DS-3). Attribution is **partial by event
  kind**: claim and artifact writes carry the acting identity
  (caller-asserted); status transitions record only the change
  (`updated:status`, no stamped actor) — no caller can stamp a transition
  actor because the bare shape accepts none [SYS §Interfaces 1, amended
  by T-097]. Errors: none at the attribution level (the bare shape makes
  stamping structurally impossible); unknown id → not-found. The
  amendment's evidence contracts (marks, review batch, cutoff) are the
  sibling's C-A1/C-A2 — consumed, not re-decided here.
- **C-M1-5 `planSprint(stage, goal, tickets) -> SprintRecord` /
  `closeSprint(record) -> Digest`** (`task_plan_sprint` /
  `task_close_sprint`) — the sprint-record lifecycle owner (DS-2). Close
  semantics: sprint-backlog tickets left unclaimed return to
  product-backlog; **claimed (`in-progress`) tickets keep their claims**
  — claims are ticket state, not sprint membership (live-evidenced: a
  claim survives arbitrarily many tick boundaries). Reopening an open
  `ticker-<stage>` record is idempotent under the U-108 reopen
  semantics. Errors: unknown record id → not-found; close with an empty
  sprint → legal, an empty digest.
- **C-M1-6 `addArtifact(ticketId, kind, ref) -> Ticket`**
  (`task_add_artifact`) — the store-side artifact index ([DSG §7]): the
  ref is recorded verbatim (repo-path or artifacts-store shape); the ref
  is actor-stamped in history (the product's provenance). Errors: the
  verb records the ref as given — **resolvability is checked at review
  time** (C-M4-1), not at write time; a definition-stage ticket's
  `done` with an empty index is refused by the done-time guard.
- **C-M1-7 `derive(basis) -> children` / `trace(ticketId) -> rows`** —
  the link model: division/link creation and `task_traceability`. A
  division creates the downstream ticket(s) with `epic` = the upstream's
  epic (scope), `basis` = the upstream id (derivation edge), and the
  upstream's artifact references carried over — the chain carries
  content, not just IDs ([DSG §3]; [SYS §Interfaces 3]). No third link
  relation exists. Errors: the derivation read is total; creation of a
  child whose (basis, stage) derivation already exists is skipped (the
  DD-02 re-derivation idempotency reads existing `trace` rows first).

### 2.2 M2 `ticker` — control

Reality anchor: one steward session per stage on
`experimental.automations` (W1; renamed from schedules in 2.3.1), armed/disarmed by `ticker-setup.ps1`;
rolling records `ticker-<stage>` ([ARCH §Interfaces to the real code
M2]). The ticker holds the big picture, never does the discipline work,
never accepts ([DSG §1]).

- **C-M2-1 `tick(stage) -> Digest`** — the fixed five-step procedure
  (W-1); it has no decisions ([DSG §4] "determinism is the point").
  Inputs: the stage's data parameterization (division map, discipline
  skill, record name). Outputs: the tick digest + the closed sprint
  record. Errors: per-step degradation only — an empty READY set costs
  one thin readiness query and closes; a claim lost to contention skips
  that ticket until the next tick; an escalation whose target is itself
  blocked flags NEEDS-HUMAN (the blocker state) and stops re-routing.
- **C-M2-2 `admit(ready[]) -> spawns[]`** — kickoff admission under the
  global spawn cap (default 4) plus per-stage serialization on
  overlapping file lanes ([DSG §5]; AD-004; E4). Inputs: the READY
  tickets with their lane labels. Outputs: the spawn set (one background
  subagent per claimed ticket). Errors: cap exhausted → the surplus
  tickets stay READY for the next tick (never queued in-session);
  overlapping lanes serialize — no ticket is dropped.
- **C-M2-3 `divide(doneBasis) -> children`** — the division engine
  (E1); runs the stage's division map over a done basis: the definition
  child AND its paired `test-*` child ([DSG §3] paired verification at
  division), `epic` inherited, `basis` = the done ticket, artifact refs
  carried. Errors: a partially completed division heals at the next
  tick (DD-02) — the engine re-reads existing derivations and creates
  only the missing children.
- **C-M2-4 `escalate(blocked[]) -> routes`** — the blocked-escalation
  router (E1 `task_set_blocked`): resolution-first (U-050) — a blocked
  ticket goes back to its **basis** as a rework request; if the basis is
  itself blocked, or the round-trip repeats, the ticket surfaces
  NEEDS-HUMAN (the blocker state, the only human surface, NFR-003).
  Inputs: the stage's BLOCKED tickets. Outputs: basis rework requests or
  NEEDS-HUMAN flags. Errors: an unresolvable route (no basis — entered
  the pipeline with no upstream) leaves the ticket blocked with the
  reason recorded; it is never silently dropped.
- **C-M2-5 `close(record) -> Digest`** — the micro-sprint close (E1
  `task_close_sprint`): planned-but-unclaimed tickets return to
  product-backlog, the digest is recorded, the record's history stays
  per-tick ([DSG §4]; U-061). Errors: none beyond C-M1-5's.

### 2.3 M3 `worker` — execution

Reality anchor: native background subagents (2.3.0 semantics: spawn
returns immediately, reports on idle); the spawn prompt IS the narrow
brief; the worker needs write access only for its own ticket
([ARCH §Interfaces to the real code M3]; [DSG §5]).

- **C-M3-1 `consume(brief) -> plan`** — the brief consumer (DS-5). The
  worker's world is exactly the brief: ticket content, direct basis
  artifacts, its stage skill + the common `stage-pipeline` skill (E6),
  lane constraints. No epic tree, no other tickets, no store-wide
  visibility ([DSG §5]). Errors: a brief whose transitive need is not IN
  the basis artifact (the chain carries content, not just IDs — AD-005)
  fails fast: the worker blocks back to its basis via C-M3-2's failure
  route instead of wandering.
- **C-M3-2 `fulfill(brief) -> in-review`** — the fulfillment executor;
  the write sequence in order (DD-01):
  1. produce the stage product in the declared file lane (the shared
     tree, AD-007 — no worktree isolation);
  2. `task_add_artifact` — actor-stamped event (E2);
  3. `task_add_comment` — the **fulfillment comment**, the worker's
     identity evidence for its flip, naming its skills (E2);
  4. `task_update` → `in-review` — a **bare** transition, the worker's
     LAST write (E2).
  Errors: an unresolvable blocker (missing/wrong upstream, lane
  conflict) → `task_set_blocked` on **its own ticket** with the reason
  naming what is needed (E2; DD-04 — the ticket resumes to
  sprint-backlog and the next tick re-claims it); an attempted `done` —
  no such operation exists in the worker's surface (the M2/M3 → `done`
  denial, AD-003).
- **C-M3-3 `load(stage, role) -> SkillSet`** — the skill loader (E6):
  common `stage-pipeline` + the stage's discipline skill
  (`VStages.SKILL`). Errors: an unmapped stage fails the **brief
  composition at M2** before spawn (fail-fast); no fallback copy of a
  discipline skill is ever created ([DSG §6] one common process, ten
  parameterizations).
- **C-M3-4 `editLane(lane, product) -> commit`** — the lane editor: all
  file edits stay inside the ticket's declared lane; the worker commits
  its lane (message citing the ticket id) so the gate + commit close the
  loop ([DSG §5]; AD-007). Errors: a needed edit outside the lane is not
  performed — it is a blocker for C-M3-2's failure route; the lane
  constraint is a store convention (labels/notes on the ticket), not git
  plumbing ([DSG §5]).

### 2.4 M4 `verdict` — governance

Reality anchor: the engine review pass + the per-project quality gate
(gate configuration is project-owned, never re-decided here); the ONLY
`done` authority ([ARCH §Interfaces to the real code M4]; AD-003).
Boundary note (A-004 N-2): the engine-verdict comment and the FAIL-block
`task_set_blocked` are the amended basis's own mark/routing mechanisms
([SYS §Interfaces 1] as amended) — part of M4's surface, not violations
of the denied list.

- **C-M4-1 `review(ticketId) -> Verdict`** — the review reader (E3
  reads: `task_get` / `task_traceability`). Inputs: ticket, artifact
  index, history, comments; the gate result (E7). Outputs:
  `Verdict ∈ {PASS, FAIL, UNCLEAR}` with the evidence located. Errors:
  an unresolvable artifact ref → the verdict is FAIL with that reason
  (the chain must carry content); no artifact on a definition-stage
  ticket → FAIL; a criterion that cannot be decided from store evidence
  → UNCLEAR (DS-05), never a guess.
- **C-M4-2 `gate(tree) -> GateResult`** — the central quality gate (E7):
  build/tests/lint on the shared tree after each landing. Inputs: the
  tree. Outputs: pass/fail + findings. Errors: a gate failure is review
  input (it fails the ticket under review), never a store write by
  itself; M2/M3 never invoke this contract (the M2/M3 → gate denial).
- **C-M4-3 `applyVerdict(ticketId, verdict) -> transition`** — the
  verdict applier. PASS → the `done` transition with the engine's mark
  (mark-1 annotated done or mark-2 in-batch verdict comment — the
  sibling's C-A2 owns the mark shapes; E3; the store's done-time guard
  refuses a definition-stage `done` with zero artifacts and the contract
  surfaces that refusal as an error). FAIL → `task_set_blocked` in the
  ticket's **own stage** with the reviewer's reasons (the routing
  mechanism named by N-2); the basis escalation then rides M2's BLOCKED
  step at the next tick. UNCLEAR → a doubt comment, no status change
  (DS-05). Errors: any `done` attempt from M2/M3 context is
  structurally unreachable (denied edge); a `done` on/after 2026-11-15
  without either mark is not decidable as engine-set and fails D-3
  ([SYS §Interfaces 1]) — the contract never produces one.

### 2.5 M5 `process` — static knowledge

Reality anchor: the skill files themselves — `.opencode/skills/stage-pipeline/SKILL.md`
plus the per-stage skill family (`software-*`, `test-software-*`;
`VStages.SKILL`); loaded by actors, never an active process ([ARCH
§Interfaces to the real code M5]; [DSG §6]).

- **C-M5-1 `load(stage, role) -> SkillSet`** — the common skill's
  parameterization contract: every stage's actor loads the SAME
  `stage-pipeline` procedure text; the per-stage part is data (the
  discipline skill, the division map), not divergent copies ([DSG §6]).
  Inputs: stage id, role (ticker loads common only — E5; worker loads
  common + stage — E6). Outputs: the skill set. Errors: an unmapped
  stage/role fails composition at the caller (C-M3-3's fail-fast); a
  null-stage (non-pipeline) ticket never reaches a stage ticker's READY
  set, so the loader is only ever invoked for staged tickets.
- **C-M5-2 `parameterize(stage) -> StageData`** — the per-stage data
  record: the division map, the discipline skill, the artifact shapes.
  Process change flows by editing `stage-pipeline` ONCE and every stage
  inherits it; adding a stage = adding its division map + skill, nothing
  else ([DSG §4 §6]). Errors: an incomplete StageData (division map
  names an unknown stage) fails at ticker arming time, not mid-tick.

## 3. Design patterns used

- **Blackboard (pass-through, AD-001)** — the store as the single shared
  substrate with independent actors around it; chosen upstream, consumed
  here by every contract. Not re-chosen.
- **Template Method — C-M2-1 `tick`** — the invariant five-step skeleton
  with per-stage data hooks (division map, skill, record name); exactly
  [DSG §4]'s "abstract procedure, concrete stage". The pattern is the
  named form of the design's own determinism requirement, not new
  machinery.
- **Facade — the `task_*` verb surface** — all actors program against
  the verb surface, never the store files (the write discipline as an
  interface: any actor → store files directly is a denied edge, [ARCH
  §Dependency rules]).
- **None introduced beyond these.** The remaining contracts are calling
  conventions over existing verbs and native mechanisms; a further
  pattern here would be weight, not design.

## 4. Data structures

### DS-1 · Ticket schema usage (the store's record, consumed by all modules)

Fields as the store's schema defines them (observed on every staged
ticket); usage rules per field group; invariants enforced by M1:

| Field group | Fields | Usage rules (who writes, when) |
|---|---|---|
| Identity | `id`, `title`, `type`, `description` | set at creation; description carries the scope + governing definitions (the brief's anchor) |
| Stage ownership | `stage` | set at creation, **never rewritten** (M1 refuses); `null` legal for non-pipeline tickets [DSG §2] |
| Links | `epic`, `basis` | set at creation by division (C-M1-7); `epic` = scope container, `basis` = derivation edge — no third relation [DSG §3] |
| Status machine | `status`, `blocked`, `resume_to`, `blocker` | the ordinary walk within the stage (`product-backlog → sprint-backlog → in-progress → in-review → done`); `blocked` is a state with `resume_to` (default sprint-backlog) + `blocker` reason; done/paused can never be blocked |
| Assignment | `assignee` | set only by the atomic claim (C-M1-2); survives tick boundaries and sprint closes |
| Planning | `sprint`, `story_points`, `priority`, `role`, `model` | `sprint` = record membership (DS-2); `role` steers dispatch (stage → role → skill); `model` optional cost lever — a local setting, never a committed value |
| Stage product | `acceptance_criteria`, `artifacts[]` | criteria set at creation/division (the paired verification derives from them); `artifacts[]` is the store-side index, appended only (C-M1-6); ≥1 artifact required before a definition-stage `done` |
| Knowledge | `labels`, `todos`, `comments[]`, `history[]` | `comments[]` carries the fulfillment comment and verdict comments (caller-asserted `by`); `history[]` append-only (DS-3); `todos` list steps within one change, never a decomposition |

Invariants (M1-enforced): `stage:` immutability; append-only history;
atomic claim as the only serialization point; the done-time artifact
guard; every write through the verbs (no hand edits — the documented
smallest-correction exception is a store convention with `task_get` +
`task_doctor` verification and a ticket comment, not an architecture
edge [ARCH §Dependency rules denied list]).

### DS-2 · Sprint record lifecycle (`ticker-<stage>`)

```text
DORMANT ──(tick step READY: kickoff, task_plan_sprint)──► OPEN
   ▲                                                        │
   │        (claims issued; spawns run in background;       │
   │         record stays OPEN only within the tick)        │
   └───────(tick step CLOSE: task_close_sprint)─────────────┘
            planned-but-unclaimed → product-backlog;
            claimed tickets keep claims; digest recorded (U-061)
```

- One named record per stage (`ticker-<stage>`), **reopened at kickoff
  and closed at the tick boundary** (U-108 reopen semantics) — the
  namespace stays compact while history, goal and digest stay per-tick
  ([DSG §4]).
- The auto-minted `S-xx` records remain for human-run waves — no new
  sprint construct is introduced.
- Error paths: close with unclaimed planned tickets returns them to
  product-backlog (existing semantics); a claim is ticket state, not
  sprint membership, so it survives arbitrarily many close/reopen
  cycles (live-evidenced by this very ticket's orphaned-claim recovery).

### DS-3 · History event semantics

- **Append-only, ordered by append time; no rewrite, no delete** — the
  property every D-point's decidability rests on (QA-1).
- **Attribution is partial by event kind** [SYS §Interfaces 1, amended
  by T-097]: `claim` and artifact-add events carry the acting identity
  (caller-asserted, recorded as given); status transitions
  (`updated:status`) record only the change — **no stamped actor**; the
  annotated `done` (mark-1) is the observed exception shape. Identities
  are caller-asserted peer conventions, not authenticated; engine
  attribution rests on the review pass's observable marks read together
  with the `in-review` → `done` ordering — the mark shapes, review
  batch, cutoff and grandfathering are the sibling's C-A1/C-A2/C-A3
  (consumed here, not re-decided).
- Observed kinds this design's workflows produce: `created`,
  `planned into <sprint>`, claim, artifact-add, comment, bare status
  transitions, `released by <actor>`, division-recorded events on the
  children's creation, the marked `done`.
- Fulfillment-chain evidence shape (D-1): `[claim(stamped) →
  artifact(stamped) → fulfillment comment → in-review(bare)]` on a
  ticket whose `stage:` never moved, no `done` inside the window —
  `FulfillmentChain` per the sibling's data structures.

### DS-4 · Readiness states (the tick's whole observation step)

`READY / WAIT_UPSTREAM / STALE / BLOCKED / RUNNING / NOT_APPLICABLE`
([AGENTS.md H6]; [SYS §Interfaces 3]): READY = upstream done AND inputs
unchanged since the last run; WAIT_UPSTREAM = basis not yet done; STALE
= an epic-chain upstream changed after the downstream ran (the
always-on consistency check, `inputs_changed_at` stamps, U-052); BLOCKED
= the blocked state; RUNNING = claimed/in flight; NOT_APPLICABLE = no
stage, done-with-fresh-inputs, or entered with no upstream (never
re-dispatched). STALE handling is a re-run trigger, not a new state —
the ticker treats STALE like READY-with-rework-flag under the same
admission path (the store's own semantics; nothing added).

### DS-5 · The brief (the spawn prompt's content)

Fixed by [DSG §5], recorded here as the data M2 composes per spawn:
ticket content + **direct** basis artifacts (content, not just IDs —
AD-005) + the stage's discipline skill + the common `stage-pipeline`
skill + lane constraints. No epic tree, no initiative context, no other
tickets, no store-wide visibility. The worker needs write access only
for its own ticket. Consequences designed for: small prompts, cheap
workers, no wandering (the worker never learns other tickets exist).

## 5. Key workflows

Every step names its executing module and the edge it rides — no orphan
steps, no unnamed executors (the workflow boundary rule).

### W-1 · The tick procedure (executor: M2; edges E1, E4, E5)

The fixed five steps in [DSG §4]'s exact order — escalation-before-launch,
no step added, none reordered ("determinism is the point"). M2 loads the
common skill only (E5); it does no discipline work.

1. **SCAN** (M2, E1 `task_readiness`) — the stage's tickets via
   readiness; this is the whole observation step (DS-4).
2. **BLOCKED → ESCALATE** (M2, E1 `task_set_blocked`) — resolution-first
   (C-M2-4): a blocked ticket returns to its **basis** as a rework
   request; basis blocked or round-trip repeated → NEEDS-HUMAN (the
   blocker state).
3. **DONE → DIVIDE** (M2, E1 division/link creation) — process forward
   per the division map (C-M2-3): definition child + paired `test-*`
   child, `epic` inherited, `basis` = the done ticket, artifact refs
   carried; the next stage's READY set grows. Re-derivation idempotent
   (DD-02).
4. **READY → KICKOFF** (M2, E1 `task_plan_sprint` + `task_claim`; E4
   spawn) — claim under the spawn cap, lane-serialized (C-M2-2), spawn
   one goal-driven worker per ticket with the brief (DS-5). The same
   loop step also admits STALE re-runs (DS-4).
5. **CLOSE** (M2, E1 `task_close_sprint`) — the micro-sprint record
   closes (DS-2); the digest is recorded; next tick repeats.

The same loop runs whether the backlog is empty (steps 1–2 find nothing,
step 5 closes — one thin readiness query) or full. **Boundaries:** M2
touches only M1 (E1), spawns M3 (E4), loads M5 (E5) — never `done`,
never the gate, never another ticker.

### W-2 · The claim-spawn-fulfill-verdict-divide cycle

The end-to-end cycle for one ticket; each step's evidence is exactly
what the D-point flows name ([ARCH §D-point evidence flows]; [SYS
§End-to-end data flows]).

| # | Step (executor, edges) | Action | Evidence produced (store-observable) |
|---|---|---|---|
| 1 | **CLAIM** (M2, E1) | readiness marks the ticket READY; `task_claim` wins it atomically → `in-progress` | claim event, actor-stamped; `assignee` set |
| 2 | **SPAWN** (M2 → M3, E4) | one background subagent, the brief composed (DS-5), under the cap, lane-serialized; reports on idle | the spawn itself (native machinery); no store write |
| 3 | **FULFILL** (M3, E2 + E6) | the worker loads common + stage skills (E6), produces the stage product in its lane, commits the lane (C-M3-4), then the DD-01 sequence: artifact → fulfillment comment → bare `in-review` flip (its LAST write) | artifact event (stamped) + fulfillment comment (identity evidence, skills named) + bare `in-review` transition |
| 4 | **VERDICT** (M4, E3 + E7) | review reads ticket + artifacts + history (E3), runs the gate (E7); PASS → marked `done` (marks per sibling C-A2); FAIL → blocked in its own stage (N-2 routing), basis escalation at the next tick's step 2; UNCLEAR → doubt comment, stays `in-review` (DS-05) | the engine's mark + `done` (D-3), or the block/doubt record; no worker/ticker `done` anywhere |
| 5 | **DIVIDE** (M2 at its next tick, E1) | the downstream ticker's DONE step creates the children per its division map (definition + paired `test-*`), links carried | children with `basis`/`epic` + carried artifact refs; WAIT_UPSTREAM → READY once the basis is done (D-2) |

Decidability invariant: reading M1 alone, the chain in rows 1→4 decides
D-1 and D-3 end-to-end; row 5 + the next tick's SCAN decides D-2. The
cycle never uses a denied edge: no M3 → M2 report (the idle return is
E4's mechanism), no lateral handoff (the store is the only channel), no
`done` outside M4, no store-file hand edits.

### W-3 · Shutdown-mid-pass reconciliation

What an interrupted run leaves behind, and how the run recovers — with
**no session memory and no invented recovery machinery** (QA-4, AD-007):
the store is the only memory (M1); every heal is re-derivation at a tick
boundary or a git operation on the shared tree.

| Interruption point | Left behind | Heal route (executor, mechanism) |
|---|---|---|
| after CLAIM, before/during spawn | an orphaned claim, no live worker | M2's watchdog at the next tick (E4 `experimental.job.list` probing, W13) finds no live job → abort; the ticket is set blocked with a blocker note, `resume_to` → sprint-backlog (E1 `task_set_blocked`, DD-06) — returned, never silently left `in-progress`, never double-spawned while a worker might live |
| during FULFILL (stalled spawn) | partial product in the lane | same route: abort + blocked-with-note (DD-06); the re-claim re-fulfills the lane from scratch ([DSG §5] watchdog semantics) |
| after artifact, before flip | product + artifact recorded, ticket still `in-progress` | re-fulfillment at the next claim re-runs C-M3-2; the append-only history shows the earlier aborted chain — the LAST fulfillment chain is the evidence, earlier chains remain visible (DS-3) |
| at `in-review`, reviewer gone | a durable waiting state — nothing lost | M4 reviews whenever it next runs (C-M4-1); `in-review` needs no live process |
| mid-DIVIDE (tick died creating children) | some children created | DD-02: the next tick's DONE step re-reads existing derivations (`task_traceability`) and creates only the missing children — the store carries no in-tick state to lose |
| shared-tree damage (a broken worker) | bad file content in a lane | git revert — never worktree isolation (explicitly rejected, [DSG §5]): lane-scoped damage is healed by the lane's next fulfillment rewriting its product (C-M3-4); cross-lane damage is a maintainer revert surfaced NEEDS-HUMAN via the blocked state; the gate + commit close the loop (C-M4-2) |
| host/service restart | dormant schedules, dead sessions | re-arm restores the tickers (all state in M1); the first tick re-derives everything from readiness — exactly the flow this ticket's own orphaned-claim recovery followed live |

**Boundaries:** every heal rides E1 (store verbs), E4 (spawn probing/abort)
or the AD-007 git route on the tree; none uses session memory, a
parallel ledger, or a recovery module — the five-module set is complete.

## 6. Error handling (consolidated)

| Condition | Contract-level behavior |
|---|---|
| Two actors claim one ticket | atomic claim (C-M1-2): one winner, loser observes `null`, no partial state |
| Spawn cap exhausted | surplus tickets stay READY (C-M2-2); never queued in-session; next tick admits |
| Overlapping file lanes | serialize within the stage (C-M2-2); no ticket dropped |
| Worker hits a blocker (missing/wrong upstream, lane conflict) | `task_set_blocked` on its own ticket, reason recorded, `resume_to` → sprint-backlog (DD-04); the next tick re-claims or escalates to the basis (W-1 step 2) |
| Blocked ticket's basis is itself blocked, or round-trip repeats | NEEDS-HUMAN: the blocker state, the only human surface (C-M2-4; NFR-003) |
| Orphaned claim / stalled spawn | watchdog abort + blocked-with-note (DD-06); never left `in-progress` forever, never double-spawned |
| Definition-stage `done` with zero artifacts | refused by M1's done-time guard (C-M1-1); the review contract surfaces the refusal (C-M4-3) |
| `done` attempted by M2/M3 | structurally unreachable — denied edge (AD-003); no such operation in their surfaces |
| Unresolvable artifact ref at review | FAIL with the reason (C-M4-1); the chain must carry content |
| Uncertain criterion at review | UNCLEAR + doubt comment, ticket stays `in-review`, one retry per stage visit (DS-05) — never a guess |
| Empty READY backlog | one thin readiness query, the tick closes (C-M2-1) — the same loop, not a special case |
| Unparsable ticket file | surfaces in readiness as a doctor-visible anomaly (C-M1-3); `task_doctor` is the repair lint — never silently skipped |
| Gate failure | review input only (C-M4-2); it fails the ticket under review; M2/M3 never invoke the gate |
| Misspelled caller-asserted identity | recorded as given; identity quality is a peer-convention matter caught at read time (per the sibling C-A1/DS-005) — never a store rejection |

## 7. Pass-through record: where [DSG] fixes the structure

Where [DSG] already fixes structure, this doc records the mapping and
consumes it — the same discipline as [ARCH §Pass-through], one level
down. Positive mappings:

| [DSG] fixes | Consumed here as | (No re-decision) |
|---|---|---|
| §1 the model (actors, roles, never-does column) | the M2/M3/M4 actor contracts' "never" faces; the module set comes from [ARCH] | the role table is upstream truth, not re-tabulated |
| §2 ticket contract (`stage:` immutability, done-time guard, division trigger) | C-M1-1's refused invariants + DS-1 | the status walk and guard semantics are store-owned |
| §3 link contract (`epic` = scope, `basis` = derivation, paired test at division) | C-M1-7 + W-2 row 5 | no third relation, no new link kind |
| §4 the tick algorithm (SCAN → BLOCKED → DONE → READY → CLOSE) | W-1 verbatim + C-M2-1 | five steps, fixed order, escalation-first — unchanged |
| §5 fulfillment (narrow brief, cap, lane serialization, no isolation) | C-M3-1/C-M3-4, C-M2-2, DS-5, W-3's git-revert route | the brief, cap and no-isolation decisions are consumed as-is |
| §6 stage skills (one common skill, parameterized per stage) | C-M5-1/C-M5-2 | no second common skill, no per-stage copies |
| §7 artifacts (store index; store-or-repo-path ref shapes) | C-M1-6 + DS-1 artifact group | no artifact mechanism beyond the index + the two ref shapes |
| §8 the verdict (`in-review` by worker; engine-only `done`) | C-M4-3 + W-2 row 4 | no second `done` authority |

Negative sweep (the re-decision markers checked and **absent**): no new
ticket field (DS-1 uses only the store's observed schema); no changed
status walk; no modified or extended tick step order (W-1 is the
five-step order verbatim — DD-02 is a property of how DONE creates, not
a new step); no link relation beyond `epic`/`basis`; no replacement
brief/cap/admission mechanism (DSG §5's are consumed unchanged); no
second common skill or per-stage skill copies; no artifact mechanism
beyond the store index with the store/fallback ref shapes; no second
`done` authority. Consistency with [ARCH §Pass-through]: every mapping
above agrees with the architecture's recorded one.

Composition with the sibling: this doc designs the whole machinery
including the amendment-touched surfaces' *machinery* contracts (C-M1-4,
C-M4-3); the *evidence* contracts of the amendment (mark shapes, review
batch, cutoff, grandfathering, the amended D-1 stop sequence) are T-111's
C-A1/C-A2/C-A3 — cited where they meet, duplicated nowhere, contradicted
nowhere (this doc's attribution wording is the amended one throughout).

## 8. Dependency-rule compliance map (E1–E7)

Every edge and denial of [ARCH §Dependency rules] against the contracts
and workflow steps that ride or avoid it:

| Edge / denial | Upheld by |
|---|---|
| **E1** M2 → M1 (`task_readiness`, `task_plan_sprint`, `task_claim`, division/link creation, `task_close_sprint`, `task_set_blocked` escalation) | W-1 steps 1–5; C-M2-1..C-M2-5 — the ticker's every store touch is on this verb list, nothing outside it |
| **E2** M3 → M1 (`task_get`, `task_add_artifact`, `task_update` → `in-review`, `task_add_comment`, `task_set_blocked`) | W-2 row 3 (DD-01 sequence); C-M3-2 — the worker's write surface is exactly its own ticket |
| **E3** M4 → M1 (`task_get`/`task_traceability` reads + the `done` transition) | W-2 row 4; C-M4-1/C-M4-3 — plus the N-2 boundary note: the verdict comment (comment verb) and the FAIL-block (`task_set_blocked`) are the amended basis's own mark/routing mechanisms, consistent, not violations |
| **E4** M2 → M3 spawn (background subagent per claimed ticket, under the cap, lane-serialized; idle report — no reverse channel) | W-1 step 4, W-2 row 2; C-M2-2; the M3 → M2 denial is upheld by the idle-return mechanism (no mid-flight orders, no report channel into the ticker) |
| **E5** M2 → M5 (common skill only) | W-1; C-M2-1 — the ticker executes process, never discipline |
| **E6** M3 → M5 (common + stage discipline skill) | W-2 row 3; C-M3-3/C-M5-1 |
| **E7** M4 → repo tree (the central quality gate) | C-M4-2; W-2 row 4 — the gate is M4's input only; M2/M3 never run it to claim completion |
| M1 → anything denied | M1 has zero outgoing edges: no callbacks, no notifications — discovery is by read at the tick boundary (every consumer contract pulls; nothing is pushed) |
| M3 → M2 denied | no worker contract addresses the ticker; the idle return is E4's own mechanism |
| M3 ↔ M3, M2 ↔ M2 lateral denied | no contract has a peer parameter; cross-stage work waits at tick boundaries (W-2 row 5) |
| Any actor → store files directly denied | C-M1-1's write discipline: every store touch is a verb call; the documented smallest-correction convention is a store rule with doctor verification, not an edge |
| M2 / M3 → `done` denied | C-M4-3 is the only applier; C-M3-2's error face names the denial |
| M2 / M3 → the gate denied | C-M4-2's error face; W-2 row 4 runs the gate inside M4 only |
| M3 → repo tree (NOT an edge — the product substrate) | the worker's lane edits + lane commit ride AD-007/[DSG §5] (no isolation): the tree carries file products, never coordination — the only coordination substrate is M1 |

## 9. Acceptance criteria mapping (T-095)

- **AC-1** — design artifact exists: components/contracts/workflows
  derived from [ARCH], repo doc convention header included → this
  document: the About header above; §2 contracts (all five modules,
  inputs/outputs/errors); §4 data structures (the three named: DS-1
  ticket schema usage, DS-2 sprint record lifecycle, DS-3 history event
  semantics); §5 the three key workflows.
- **AC-2** — every workflow names its module boundary and stays within
  the allowed edges E1–E7 → §5 (every step annotated with executor +
  edges) and §8 (the compliance map; no denied edge used anywhere).
- **AC-3** — pass-through mapping recorded where [DSG] already fixes the
  structure → §7 (positive mappings + the negative sweep).
- **AC-4** — artifact recorded on this ticket before in-review → store
  discipline: `task_add_artifact` (kind `doc`) is written before the
  `in-review` status update; the fulfillment comment names both skills
  (`software-design` + `stage-pipeline`); `done` is never self-assessed
  — it is the engine review pass's verdict.

## 10. Lane and non-goals

This ticket's lane is one new doc file
(`docs/acceptance-run-design.md`). Not touched:
`docs/acceptance-run-design-amended.md` (T-111's lane — consulted
read-only, never edited), `docs/acceptance-run-design.tests.md` (the
criteria — T-096's product), `docs/acceptance-run-architecture.md`,
`docs/acceptance-run-system.md`, `docs/design-stage-pipeline.md`, any
code, any skill, any store file. No concrete provider or model ids
anywhere (D-004 — the optional per-ticket `model` field is named as a
schema field only). The contracts are specified, not scripted;
component *realization* belongs to the implementation stage; the
contracts' verification belongs to the paired `test-design` stage
(T-096), whose CT-D-001..CT-D-007 decide this artifact. Downstream
hand-off: implementation derives from §2/§4/§5; `test-design` tests
against the §2 contracts, §5 workflows and §7 pass-through record.
