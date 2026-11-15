# Live-in-Eclipse acceptance recurrence — architecture (T-107)

## About this document

- **Kind:** `doc` / architecture-stage artifact of ticket T-107 (epic U-113,
  basis T-085, stage `architecture`; discipline `software-architecture`): the
  internal structure of the live-in-Eclipse acceptance recurrence —
  components, per-component responsibilities and explicit dependency rules,
  derived from the governing system flow. Where the system flow or the
  pipeline design already fixes the structure, this document records the
  mapping and the pass-through rationale instead of new structure.
- **Read by:** the design stage (this is its basis — what the design of each
  component must uphold), the reviewer (traceability check against the system
  flow), the ticker steward and the kickoff assistant (the actors whose hook
  points this fixes), and the maintainer (the product owner who executes
  every live pass); **written by:** T-107 (architecture stage).
- **Related:** `docs/live-in-eclipse-recurrence-flow.md` (T-085 — the
  governing system flow, read-only basis); `docs/design-stage-pipeline.md`
  (the tick machinery — pass-through, not re-derived here);
  `.opencode/skills/stage-pipeline/SKILL.md` (the live tick steps — reference
  for reality); `docs/release-cadence-flow.md` (T-093's sibling walk —
  composition recorded in §7, neither file modified); `docs/TESTING.md` (the
  live-pass checklist); `AGENTS.md` (paired-verification paragraph; the
  chat-first control plane); tickets T-039 (the rule), T-002 (the origin
  instance), T-040 (the first minted instance).

## 1. Chosen pattern

Evaluated against what the system flow fixes (§1 of
`docs/live-in-eclipse-recurrence-flow.md`: four existing moving parts and no
new ones — the tick steps, the store states, the owner's live pass, the
engine verdict; §6: six non-blocking guarantees, each against a real
dependency direction in the dispatch machinery):

- **Layered (N-tier):** wrong shape — the recurrence is not a stack of
  transformation layers; it is one mint function with two hook points and a
  human-paced executor.
- **Client-server / SOA / microservices:** no process or service boundaries
  to decompose — every participant is a peer over one store (AGENTS.md: no
  agent owns or messages another; coordination is store-mediated).
- **Event-driven:** rejected deliberately. The pipeline is *polled*, not
  evented (`docs/design-stage-pipeline.md` §4: "the tick boundary is the only
  synchronization point"), and the flow converges by re-deriving — sweep +
  dedup — not by subscribing: a mint lost mid-pass is re-minted by the next
  sweep (flow §6, convergence).
- **Repository (shared data store):** fits exactly. The mint, the dedup
  scan, the owner's evidence, the verdict and every guarantee ride store
  states that already exist; the flow adds no new store concept, no new
  ticket type, no new tool (flow §1).
- **MVC / CQRS / P2P:** MVC has no view/model split here; CQRS splits
  read/write models the store deliberately does not have; P2P (unstructured
  peers) is what the store exists to avoid.

**Chosen: shared-repository coordination over the task store, with the
recurrence as swept derivation inside the existing tick loop.** Why it fits:
every relationship in the system view (ticker mints → instance sits → owner
executes → engine verdicts) is a store access, and the store already carries
the invariants the guarantees lean on (claims, readiness, the blocked-state
machine, history events). What it costs: discovery is polling-only — the
owner finds open instances on the Board, the sweep re-derives candidates each
pass. For this system that is free: one consumer is tick-bound (the ticker),
the other human-paced (the owner).

## 2. Components

The system flow fixes the parts (§1: four moving parts; §2: the actors). This
section records the mapping — where structure was already fixed, the
rationale is pass-through, not new design. Two deliberate refinements, both
naming moves for the rules' sake:

1. The system flow's "tick steps" part is named at its two recurrence hook
   points as C-1, and the fallback route is split out as C-2 — the kickoff
   assistant solely owns a whole trigger path (the R3 backfill) plus the
   no-ticker fallback, with its own constraint set (announced in chat, same
   template, never ad-hoc).
2. The minted ticket itself is not a component: it is the data contract every
   component shares, so it is specified as the instance contract (§3) — every
   guarantee and every trace row keys on its fields.

| # | Component | Reality anchor | Responsibility | Depends on |
|---|---|---|---|---|
| C-1 | **Mint extensions** (tick step 4 + step 6) | `.opencode/skills/stage-pipeline/SKILL.md` tick step 4 (division — the LIVE-CHECK PAIRING extension) and step 6 (close — the WAVE-COMPLETION RECURRENCE extension), wording verbatim in flow §5.1; the `ticker-v` steward session (`ticker-setup.ps1`) | **R1:** per done definition ticket D at step 4, immediately after D's ordinary division children — scan D's acceptance criteria for live-in-Eclipse checks, derive the source token, dedup-scan, mint the pairing instance (`basis` = D, `epic` = D's epic), set `in-progress` + `owner-acceptance`; never plan it into a sprint record, never spawn a worker for it. **R2:** once per pass at step 6, after the digest, before the record closes — sweep sprint records for completed waves (id not `ticker-`, ≥1 ticket, zero open), dedup, mint the wave instance (`basis` = null, `epic` = the wave's single common epic or null), note the mint in the pass digest (U-061). Never mints at a release cut (R3 → AD-004) | C-3 only |
| C-2 | **Kickoff assistant fallback** | the main chat session (H7, AGENTS.md chat-first control plane) | fallback minter when no ticker is armed (I1): same template, announced in chat; the ONLY routine executor of the R3 backfill mint (a release cut whose waves predate the rule → one release instance, token `(release vN.N.N)`). Never mints ad-hoc acceptance tickets outside the template | C-3 only |
| C-3 | **Task store** | `.harness/tasks/hephaestus/` + the `task_*` API; the state machine (AGENTS.md); the Eclipse Board as its projection | holds every instance in exactly the contract shape (§3): `test-requirements` (immutable stage ownership), `in-progress` + `owner-acceptance`, no sprint membership, `basis`/`epic` per instance class, the source token in the title; provides the dedup scan surface (title scan over the project's `test-requirements` tickets, open or done); runs the dispatch machinery whose branches define G1 (claims take `sprint-backlog` only, readiness reports RUNNING for `in-progress`, `task_close_sprint` ejects only record members); carries the history-event audit trail and the invariants the guarantees lean on (done is never blocked; claims pre-check the candidate's assignee) | — (substrate) |
| C-4 | **Owner live pass** (the T-002 pattern) | the maintainer at the Eclipse keyboard; checklist `docs/TESTING.md` | executes the instance's live checks (console checks, UI passes, panel round trips); records the evidence — a screenshot or panel transcript reference — as an artifact on the instance (I7); one pass may evidence several open instances (shared checklist), each instance's verdict independent; flips the instance to `in-review`; files findings as bug tickets (they ride the next wave); on a failed pass: records the failed evidence, blocks the instance (resume to sprint-backlog) naming the finding tickets, clears the blocker when findings land | C-3 only |
| C-5 | **Engine verdict pass** | SKILL.md tick step 3 (one read-only `reviewer` subagent per `in-review` ticket); the engine's review pass + quality gate (`docs/design-stage-pipeline.md` §8) | the only `done` authority for an instance — same as any ticket: PASS → `done`, FAIL → `blocked` (flow §5.3); never consults an instance from a parent ticket's review (G3); never accepts the ticker's or the owner's self-assessment | C-3 only |

## 3. The instance contract (the T-002-pattern template, carried over 1:1)

The minted acceptance ticket is the system's data contract. Every field value
below is carried over 1:1 from flow §3.1 — the third column names the
component(s) that write or read the field, nothing else changes.

| Field | Pairing instance (R1) | Wave instance (R2) | Carried by |
|---|---|---|---|
| `title` | `Acceptance (live in Eclipse): <one-line live check> (<D id> AC<n>)` | `Acceptance (live in Eclipse): wave <record id> live pass (wave <record id>)` | minted by C-1 (or C-2 fallback); the parenthesized source token is the identity + dedup key (I6) |
| `type` / `story_points` / `priority` | `task` / 0 / medium | same | minted by C-1 / C-2 |
| `role` / `stage` | `tester` / `test-requirements` (immutable) | same | immutable stage ownership (C-3); steers the empty division map (G2) |
| `epic` | the done ticket D's epic (scope inheritance) | the wave's single common epic, else `null` | scope container only (C-3) — never a dispatch input |
| `basis` | D (the machine-readable derivation edge to the verified definition) | `null` (no single upstream ticket) | carried for traceability (C-3); G6 leans on it: any backward-gating attempt hits a `done` ticket the store refuses to block |
| `sprint` | none — NEVER planned into any sprint record | same | C-1/C-2 never call `task_plan_sprint` for it (G1) |
| `status` + `assignee` after mint | `in-progress` + `owner-acceptance` | same | the G1 shape: matches no tick branch; `owner-acceptance` is a routing marker ("awaits the owner"), never evidence of owner action — the release-cadence oracle keys on authored comments/artifacts, not assignees (its §3.1) |
| `model` | `null` | `null` | no worker ever runs it; D-004-safe trivially (DR-10) |

The release instance (R3 backfill only) uses the same template; its source
token is `(release vN.N.N)` — the third token of flow §3.4.

- **Fallback mint shape:** if the engine refuses a direct mint into
  `in-progress` (no claim walk), mint `sprint-backlog` + `owner-acceptance`
  — the assignee keeps it out of targeted claims (SKILL.md field observation:
  claims pre-check the candidate's assignee). The `in-progress` mint is
  primary because it matches no dispatch branch at all (G1).
- **Description and acceptance-criteria templates** pass through verbatim by
  reference (flow §3.2, §3.3): minters fill the live-check lines from the
  source event (D's acceptance criteria / the wave's agent-facing surfaces)
  and never reword the NON-BLOCKING paragraph. The evidence line in the
  acceptance-criteria template ("Evidence recorded as artifact on this
  ticket") is what makes I7 verdict-checkable (AD-006).
- **Identity and dedup** (flow §3.4): one instance per source token, ever —
  open or done (I6). The scan surface is C-3 (the project's
  `test-requirements` tickets, title check); convergence rides it (QA-002).

## 4. Dependency rules (explicit)

- **DR-1 — Store-only contact; no direct component edges.** Every component's
  edge lands on C-3's `task_*` interface; the components share no edges with
  each other. The mint is a store write, not a message: C-1's mint becomes
  C-4's work order purely by C-3 holding the instance. This is the shared-
  repository pattern realized — the store is the only channel, as the
  pipeline design prescribes for stages ("decoupled through the store — never
  a direct handoff").
- **DR-2 — The recurrence rides the existing tick loop; no new trigger
  machinery.** R1 hooks tick step 4 (inside the per-stage loop, immediately
  after D's ordinary division children); R2 hooks tick step 6 (once per pass,
  after the digest, before the record closes). The placement is fixed by the
  machinery's shape: steps 1–4 run per stage in canonical order, so a
  stage-local sweep could miss a wave completed by a LATER stage's verdict in
  the same pass; the close sweep sees the whole pass, runs once, and dedup
  makes re-runs harmless (flow §5.1).
- **DR-3 — Instances are never dispatch inputs (G1).** No component plans an
  instance into a sprint record (I3), claims one, or spawns a worker for one
  (I2). The minted state matches no tick branch structurally: step 5 claims
  `sprint-backlog` tickets only, readiness reports RUNNING (never READY) for
  `in-progress` work, and non-membership in any record makes
  `task_close_sprint` ejection impossible.
- **DR-4 — Nothing derives from an instance (G2).** `test-requirements` is
  the LAST V stage with an empty division map; no step ever sets an instance
  as a `basis` (I4); no readiness edge depends on one. The only derivation
  edge touching an instance points INTO it (pairing instances: `basis` = D).
- **DR-5 — Review scopes are disjoint (G3).** A parent ticket's engine review
  (C-5) never consults the instance; the instance's verdict never re-opens
  the parent. The live check moves OUT of the parent's review scope into the
  instance — established live on U-009 ("AC4 moved to its own acceptance
  ticket at test-requirements... This ticket returns to in-review for the
  remaining criteria"; flow §6). Done is never the owner's or the ticker's
  self-assessment.
- **DR-6 — Wave completion is computed on real tickets only (G4).** The R2
  sweep runs after completion is already true; the mint records evidence, it
  never defines completion. The order inside step 6 is fixed: digest written
  → R2 mint sweep (dedup first, digest note per mint) → the sibling walk's
  cadence evaluation (§7) → the record closes.
- **DR-7 — The release path reads instances and never gates on them (G5).**
  No release step writes to instances; open ones carry over and thicken the
  evidence base against the released build. The cut mints nothing (AD-004);
  the R3 backfill is a C-2 chat-side motion, never part of
  `eclipse/releng/release.ps1` — the recurrence does not touch the cut
  sequence (D-002).
- **DR-8 — Failure flows forward, never backward (G6).** A failed pass files
  bug tickets (they ride the NEXT wave), records the failed evidence, and
  blocks the INSTANCE (resume to sprint-backlog) naming the finding tickets —
  a NEEDS-HUMAN-shaped signal aimed at the very actor who executes the pass.
  The basis, when set, is `done`, and the store refuses blocking done
  tickets, so the verified definition cannot re-open. Only C-4 clears the
  blocker.
- **DR-9 — Composition with the sibling walk is read-only across the seam.**
  The release-cadence flow (T-093) reads C-3's instances as review evidence
  and comments the cadence outcome onto them; neither side mints cadence
  tickets of its own, and neither flow modifies the other's document (§7).
- **DR-10 — No concrete provider or model id in committed content (D-004).**
  The contract's `model` field is `null`; nothing in the recurrence resolves
  or pins a model.

## 5. Key quality attributes

- **QA-001 — Non-blocking acceptance** (why: T-039 — acceptance hardens and
  never gates). The six guarantees G1..G6 are structural: each is a
  dependency rule above (DR-3..DR-8) enforced by store state and edge
  direction, not by discipline. Residual: the fallback mint shape
  (`sprint-backlog`) leans on the claim walk's assignee pre-check — the
  SKILL.md field observation; the primary shape carries G1 structurally.
- **QA-002 — Convergence / idempotence.** Dedup on the source token makes
  every mint idempotent and convergent; sweeps re-derive what was never
  confirmed (the tasks namespace can drop mid-run); the store never loses a
  confirmed write. No delivery guarantee is needed anywhere in the structure.
- **QA-003 — Auditability.** Every mint, verdict and status flip leaves a
  store history event with `by` provenance; mint notes land in the pass
  digest (U-061); owner evidence is recorded as artifacts (I7). Nothing in
  the recurrence happens off the record by construction.
- **QA-004 — Owner economy.** One live pass evidences several open instances
  (shared `docs/TESTING.md` checklist, flow §5.2); one instance per source
  event ever (I6); wave-level minting — one instance per wave, not per ticket
  — keeps the pass count at one per release occasion in practice.

## 6. Architecture decisions

- **AD-001: Swept derivation over event subscription.** R2 is a close sweep
  that re-derives candidates and dedups, not a completion-event handler.
  Trade-off: one cheap scan per pass even when nothing changed; in exchange,
  no eventing machinery in a polled pipeline, and convergence replaces
  delivery guarantees.
- **AD-002: The mint lands directly in `in-progress` + `owner-acceptance`,
  bypassing the claim walk** — the only state today's machinery keeps out of
  every dispatch branch. Trade-off: bypasses claim semantics (accepted, flow
  §9); the store's history events record who minted, so the audit trail stays
  honest; the fallback shape (§3) exists if the engine refuses the direct
  mint.
- **AD-003: The source token lives in the title.** The `task_*` API exposes
  no label query today (flow §9). Trade-off: the dedup scan is textual over
  titles; if a label query lands upstream, the token moves to a label and the
  scan stays machine-readable without changing the flow.
- **AD-004: R3 mints nothing at the cut — once-per-wave coverage; the
  backfill exception is the only release-side mint**, executed by C-2,
  announced in chat. Trade-off: a release train whose waves predate the rule
  gets exactly one instance at the cut (never zero, never one per wave); the
  gating temptation is refused exactly where it is strongest — the cut (flow
  §4's three reasons; T-039/T-041).
- **AD-005: The template is rigid.** Fixed in the flow doc, carried verbatim
  by minters; the only per-instance variability is the live-check lines and
  the source token. Trade-off: flexibility lives in check content, not
  shape — the rigidity is what makes dedup, G1 and the owner's expectations
  stable.
- **AD-006: The instance's evidence floor (I7) is verdict-checked, not
  guard-enforced.** The store's done-time artifact guard covers definition
  stages (`requirements`..`implementation`, design-stage-pipeline §2); an
  instance is a `test-requirements` ticket, so its "Evidence recorded as
  artifact" acceptance criterion (flow §3.3) is enforced by C-5's review —
  the same way every acceptance criterion is. Trade-off: no automated store
  guard for the evidence line; the reviewer checklist carries it.

## 7. Composition with the sibling walk (release cadence)

`docs/release-cadence-flow.md` (T-093) is the other half of one wave-close
ruleset; this architecture records the seam and modifies neither file:

- **One sweep, two extensions, fixed order** (its §6): C-1's R2 mint first
  (creates/keeps the instance), the cadence evaluation second (reads it) —
  both at tick step 6, after the digest, before the record closes. DR-6
  carries the order.
- **One wave detection, shared verbatim**, including the `ticker-` carve-out
  (ROADMAP: micro-records are never waves) — C-1's sweep is the single
  implementation of it.
- **One instance, two readers:** this walk owns the instance's lifecycle
  (C-1 mint, C-4 execute, C-5 verdict); the cadence flow only READS it for
  evidence and comments the outcome onto it.
- **Mirrored guarantees:** its N1/N2 are G5 seen from the other side; both
  walks refuse to let acceptance or cadence states block anything.
- **Division of content:** the cadence flow adds the evidence oracle (the
  identity conventions and the query — keyed on authored comments/artifacts,
  never on assignees or verdicts) and the cut path; this architecture supplies
  the substrate those read: C-3's instances in exactly the §3 shape.

## 8. Open questions and pass-through observations

- **No open questions are carried.** The system flow's §9 items are all
  marked accepted; they are recorded here as decisions (AD-002, AD-003,
  AD-004, QA-002's wave-detection note) rather than re-opened.
- **Pass-through observation (live data, not structure):** T-040 — the first
  instance minted under the rule but before the flow existed — sits
  `sprint-backlog`, unassigned, `basis: null`, and live readiness evidence
  shows it READY via the epic-chain fallback (flow §7). The one-time
  transition (apply `in-progress` + `owner-acceptance` once, so G1 holds for
  it too) is a store motion outside this architecture's lane; until it runs,
  G1 for T-040 rests on no `test-requirements` kickoff claiming it. T-002
  itself is `done` — an instance, not the mechanism (flow §7).

## 9. Traceability matrix (T-107 AC-2)

Every trigger, every actor row and every non-blocking guarantee of
`docs/live-in-eclipse-recurrence-flow.md` → its component(s) here → the
evidence it rests on:

| System element (live-in-eclipse-recurrence-flow.md) | Component(s) | Evidence |
|---|---|---|
| §4 R1 — pairing division at tick step 4 | C-1 (hook), C-3 (dedup surface + mint write), §3 (pairing field values) | flow §4 R1 row + §5.1 STEP 4 EXTENSION; SKILL.md step 4 carries the live-in-Eclipse rule verbatim |
| §4 R2 — wave-completion sweep at tick step 6 | C-1 (hook), C-3 (wave detection + dedup surface), §3 (wave field values) | flow §4 R2 row + §5.1 STEP 6 EXTENSION; SKILL.md step 6 (close); the stage-local-vs-close rationale (flow §5.1) |
| §4 R3 — release cut: no mint; backfill exception only | position: AD-004 + DR-7; backfill executor: C-2; token: §3 (`(release vN.N.N)`) | flow §4 R3 row + the three no-mint reasons + the backfill paragraph; `docs/release-cadence-flow.md` §4.2 (the cut sequence the recurrence never touches) |
| §2 actor row 1 — stage ticker pass: ONLY routine minter; never spawns/plans/accepts | C-1; the never-rules: DR-3 (G1), DR-4 (G2); acceptance monopoly: C-5 + DR-5 | flow §2; SKILL.md steps 4 and 6; AGENTS.md (engine verdict as the only done authority) |
| §2 actor row 2 — kickoff assistant: fallback minter, same template, announced; never ad-hoc | C-2; template rigidity: AD-005 | flow §2; H7 (AGENTS.md chat-first control plane) |
| §2 actor row 3 — product owner: executes the pass, files findings, may mint or override manually | C-4; manual override is the owner's standing prerogative (the owner can override any machine rule) | flow §2; `docs/TESTING.md` (the checklist) |
| §2 actor row 4 — engine review pass: only `done` authority; never accepts self-assessment | C-5; DR-5 | flow §2; SKILL.md step 3; design-stage-pipeline §8 |
| §2 pseudo-actor `owner-acceptance` (owner as pass executor) | §3 (`assignee` field); routing marker, not evidence | flow §2 closing paragraph + §6 G1; release-cadence §3.1 (assignees are never evidence) |
| §6 G1 — never dispatched | DR-3; C-3's branches (claims `sprint-backlog` only; readiness RUNNING for `in-progress`; no record membership); AD-002 + the fallback shape | flow §6 G1; SKILL.md step 5 (claim walk) + field observation (assignee pre-check); AGENTS.md state machine; live readiness observation (T-055, T-085) |
| §6 G2 — nothing derives from it | DR-4; C-1's empty division map for the last stage; C-3 (no readiness edge into instances) | flow §6 G2; AGENTS.md V-stage table (`test-requirements` is the last stage) |
| §6 G3 — parent verdicts unaffected | DR-5; C-5's disjoint review scope | flow §6 G3; the U-009 precedent (quoted there) |
| §6 G4 — wave completion unaffected | DR-6 (mint after completion is true, fixed order); C-1; C-3 (completion computed on members) | flow §6 G4 |
| §6 G5 — release unaffected | DR-7; §7 (the cadence walk reads, never gates) | flow §6 G5; release-cadence-flow N1/N2 and §3.3 (the instance as canonical evidence, its NON-BLOCKING guarantee untouched) |
| §6 G6 — failure isolation | DR-8; C-4's failed-pass procedure; C-3 (done is never blocked) | flow §6 G6 + §5.4; AGENTS.md blocked-state invariant ("done and paused can never be blocked") |
| §6 convergence (dedup; re-mint of lost mints) | QA-002; C-3 (dedup scan surface); AD-001 + AD-003 | flow §6 closing paragraph + §3.4 |
| §3.1–§3.4 template fields — carried over 1:1 | §3 (every field value reproduced verbatim + the carrying component) | flow §3.1 field table, §3.2/§3.3 templates (by reference), §3.4 token rules |
| §8 I1..I7 invariants (summary) | I1: C-1/C-2 (mint sources only) · I2: DR-3 + §3 assignee · I3: DR-3 · I4: DR-4 · I5: DR-3..DR-8 collectively (QA-001) · I6: §3 identity rule + QA-002 · I7: C-4 + AD-006 + §3 evidence line | flow §8 |

## 10. Acceptance criteria mapping (T-107)

- **AC-1** (architecture doc exists, docs/ convention About header included,
  with component structure, per-component responsibilities and explicit
  dependency rules derived from the system flow): this document — the About
  header; §1 pattern; §2 components (with the two refinement rationales);
  §3 instance contract; §4 dependency rules DR-1..DR-10.
- **AC-2** (every trigger, actor row and guarantee traces through the
  components to its evidence; the T-002-pattern template fields carry over
  1:1): §9 traceability matrix; §3 for the 1:1 field carry-over; §7 records
  the composition with `docs/release-cadence-flow.md` by reference, neither
  file modified.
- **AC-3** (artifact recorded on the ticket before in-review; done is never
  self-assessed): store discipline — `task_add_artifact` (kind `doc`) before
  the `in-review` status update; `done` is the engine's verdict (C-5's
  monopoly, DR-5).

## 11. Lane and non-goals

This ticket's lane is one new doc file (`docs/live-in-eclipse-recurrence-
architecture.md`). Not touched: `docs/live-in-eclipse-recurrence-flow.md`,
`docs/release-cadence-flow.md`, `docs/design-stage-pipeline.md`,
`.opencode/skills/stage-pipeline/`, `AGENTS.md`, `docs/TESTING.md`, any code,
any store data. No concrete provider or model ids anywhere (DR-10/D-004).
Component *design* (interfaces, data structures, workflows inside each
component) belongs to the design stage; this document fixes only boundaries,
responsibilities and dependency direction. The T-040 transition (§8) and any
scripting of the dedup scan are other lanes.
