# Live-in-Eclipse acceptance recurrence — component design (T-119)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket T-119 (basis
  T-107, stage `design`): the inside of the live-in-Eclipse acceptance
  recurrence — component contracts (inputs → outputs | errors), data
  structures, key workflows and error behavior for the architecture's
  components C-1..C-5, per the `software-design` skill.
- **Read by:** the implementation stage (its basis — what each component's
  realization must satisfy), the `test-design` stage (component criteria 1:1
  against these contracts), the reviewer (traceability check against the
  architecture and the flow), the ticker steward and the kickoff assistant
  (the actors whose pass wordings operationalize the mint contracts), and the
  maintainer (the product owner — C-4's actor); **written by:** T-119
  (design stage).
- **Related:** `docs/live-in-eclipse-recurrence-architecture.md` (T-107 — the
  governing architecture: C-1..C-5, DR-1..DR-10, the §3 instance contract,
  AD-001..AD-006, QA-001..QA-004 — read-only basis);
  `docs/live-in-eclipse-recurrence-flow.md` (T-085 — the governing system
  flow; every contract here preserves it, none contradicts it);
  `docs/design-stage-pipeline.md` (the tick machinery — pass-through);
  `.opencode/skills/stage-pipeline/SKILL.md` (tick steps 3/4/6 — the reality
  the contracts land in); `docs/TESTING.md` (C-4's checklist);
  `docs/release-cadence-flow.md` (T-093's sibling walk — composition
  preserved by reference, §5 W-2 and §7); `AGENTS.md` (the store state
  machine; the chat-first control plane; paired verification); tickets
  T-039 (the rule), T-002 (the origin instance), T-040 (the first instance).

## 1. Design scope

The architecture (T-107) fixed the **boundaries**: five components with
responsibilities, ten dependency rules, the instance contract, and six
guarantees realized structurally. This document designs the **inside** of
each component per the `software-design` skill: contracts, data structures,
workflows, error behavior. The components are modules of a process/agent
architecture — wordings, store calls and roles over one store — so a
"contract" here specifies store calls and their order and conditions, not
code signatures; the realization surfaces already exist (the SKILL.md tick
steps, the `task_*` API, the Board, the owner's checklist).

Explicit pass-throughs, recorded so nothing is silently un-designed:

- **C-3 (task store):** substrate — the engine owns its contracts (state
  machine, write discipline, history events, claims, readiness, blocked
  invariant); §2 C-3 names only the surfaces the other contracts consume.
- **The tick machinery:** `docs/design-stage-pipeline.md` §4–§6 **is** the
  design; the recurrence hooks the two extension points (DR-2) and adds no
  step, no timer, no eventing.
- **The mint wording and the ticket template:** flow §5.1 (verbatim — I1)
  and flow §3.2/§3.3 (rigid — AD-005) are carried by reference; §4 refines
  the fields to the call level without rewording either.

Contract notation throughout: `name(inputs) -> output | errors`. Design
decisions made at this stage are recorded as **DS-001..DS-006** (at their
point of use).

## 2. Component contracts

### C-1 — mint extensions (tick steps 4 + 6)

Reality anchor: SKILL.md tick step 4 (division) and step 6 (close); contact
exclusively the `task_*` API of C-3 (DR-1). Four primitives and two
trigger-shaped composites:

- `detectLiveChecks(D: done definition ticket) -> [acLine] | []` — inputs:
  D, processed at step 4; scans D's acceptance criteria for lines demanding
  live-in-Eclipse verification (console checks, UI passes, panel round
  trips — the SKILL.md step-4 markers). Output: the matching lines, one per
  criterion. Errors: none found → `[]` → the R1 path stops; a skip is not a
  failure. Classification rule (DS-001): a doubtful line **counts** as a
  live check — a spurious pairing instance is one harmless non-gating
  live-pass line, while a skipped pairing is never re-derived (R1 keys on
  D's division); the flow's own false-trigger asymmetry (flow §9) is the
  precedent.
- `derivePairingToken(D, [acLine]) -> "(<D id> AC<n>)"` — the token of the
  FIRST matching criterion (flow §5.1 step 2). Errors: no match →
  unreachable after `detectLiveChecks` (defensive stop).
- `dedupInstanceExists(token) -> bool` — a textual title scan over the
  project's `test-requirements` tickets, open or done (DS-004). Errors:
  scan surface unavailable → the mint is **deferred** to a later pass —
  never minted blind (I6 is "one instance, ever"; an unscannable mint risks
  the duplicate that dedup exists to prevent).
- `mintInstance(spec: InstanceSpec) -> MintOutcome` — the two-call mint
  (DS-002): `task_create` with the §4 field set, then `task_update`
  (status + assignee) into the landing zone; the outcome's `confirmed` is
  read-back truth (`task_get`'s answer, never the minter's intent).
  Errors: create refused → no instance, surfaced — a lost create is safely
  re-derived (the title scan never saw it). Update refused → retry in-pass,
  then the **fallback landing zone** (`sprint-backlog` +
  `owner-acceptance`, flow §3.1 — the assignee pre-check keeps it out of
  targeted claims). Still unconfirmed at pass end → a repair item: re-assert
  the update; **never re-create** (the title scan would see the created
  ticket and dedup would skip the mint, leaving the state wrong).
- `pairingMint(D) -> minted | skipped` — **R1** (tick step 4, immediately
  after D's ordinary division children are created): `detectLiveChecks` →
  `derivePairingToken` → `dedupInstanceExists` (exists → `skipped`, I6) →
  `mintInstance` with `basis = D`, landing
  `in-progress` + `owner-acceptance`. NEVER plans the instance into a
  sprint record, NEVER spawns a worker for it (DR-3; flow §5.1 step 5).
  Audit: the store's history events carry the mint with `by` provenance
  (QA-003); no digest step (DS-003). Convergence: every step is a pure
  function of durable store state plus the token guard, so re-executing
  the extension for a visited D is idempotent (QA-002); the machinery's
  own mid-pass recovery is reconnect-and-continue (SKILL.md field
  observation).
- `sweepCompletedWaves(records) -> [minted | skipped]*` — **R2** (tick
  step 6, once per pass, AFTER the digest is written, BEFORE the record
  closes — DR-2, DR-6). Per sprint record W: `isCompletedWave(W)` → token
  `(wave <W id>)` → dedup → `mintInstance` with `basis = null`,
  landing as above → ONE digest
  note per mint (U-061, DS-003). Errors: a record failing the predicate →
  `skipped`, not a failure; a store write lost mid-sweep → the next pass's
  sweep re-derives (the sweep runs every pass; QA-002).

`isCompletedWave(W) -> bool` — `W.id` does not start with `ticker-` (the
ROADMAP carve-out: micro-records are never waves) ∧ W holds at least one
ticket ∧ zero open members (open = any status except `done`; a `paused`
member keeps the wave open). Inputs: the project's sprint records.

### C-2 — kickoff assistant fallback

Reality anchor: the main chat session (H7, AGENTS.md chat-first control
plane). Two contracts, both bounded by template rigidity (AD-005):

- `fallbackMint(sourceEvent) -> minted | skipped` — only when NO ticker is
  armed (I1): executes C-1's composite for the observed source event (the
  wave-close case the flow names — flow §9 — or a pairing it observes for a
  done definition), same template, ANNOUNCED IN CHAT before the store
  write. Errors: a ticker IS armed → this contract must not run (route
  collision — the ticker owns routine mints); any template deviation (a
  reworded NON-BLOCKING paragraph, an ad-hoc acceptance ticket) is a
  contract violation — the mint does not happen.
- `releaseBackfillMint(version) -> minted | skipped` — the **R3 exception**
  and nothing else: at a release cut whose waves predate the rule (no
  covering instance, open or done), ONE release instance, token
  `(release vN.N.N)`, announced in chat, dedup-guarded. NEVER part of
  `eclipse/releng/release.ps1` — a chat-side motion of the assistant only
  (DR-7, AD-004); the cut itself mints nothing.

Owner-prerogative note: the owner may mint or override manually (flow §2) —
outside every contract surface here; no contract attempts to prevent it.

### C-3 — task store — explicit pass-through

Substrate (architecture §2; AD-001). Its contracts are engine-owned and out
of this design's scope: the state machine, the write discipline, history
events with `by` provenance, the blocked invariant (`done` and `paused` can
never be blocked), the claim walk (targets `sprint-backlog` only and
pre-checks the candidate's assignee), readiness (`RUNNING`, never `READY`,
for `in-progress` work), record ejection (`task_close_sprint` ejects only
record members). Surfaces the contracts above consume — named so "every
element resolves" is explicit, none re-designed: `task_create`,
`task_update`, `task_get` (read-back), the project ticket listing (the
title-scan surface, DS-004), `task_add_artifact` (evidence),
`task_set_blocked` and its clear (C-4's failed-pass path),
`task_plan_sprint` (existence only — never called for an instance, DR-3).

### C-4 — owner live pass

Reality anchor: the maintainer at the Eclipse keyboard; checklist
`docs/TESTING.md`; contact exclusively the `task_*` API (DR-1). Contracts
are outcome-shaped — the owner is human; the store is the proof:

- `executeLivePass(instances: [instanceId], checklist) ->
  {evidence per instance} | [finding]` — ONE pass may evidence SEVERAL open
  instances (the checklist is shared — QA-004); per instance: work the
  instance's live-check lines in Eclipse, record the evidence artifact
  (`task_add_artifact`: a screenshot or panel transcript reference — I7),
  then flip the instance to `in-review` (flow §5.3). Verdict independence:
  instances sharing a pass are judged separately, each on its own evidence.
- `fileFindings([finding]) -> [bugTicketIds]` — findings become
  `type: bug` tickets; they ride the NEXT wave (DR-8: failure flows
  forward, never backward).
- `blockInstance(instance, [findingTicketIds]) -> blocked` — records the
  FAILED evidence artifact first, then blocks the instance:
  `resume_to: sprint-backlog`, the blocker text naming the finding tickets
  (flow §5.4; DR-8/G6 — a NEEDS-HUMAN-shaped signal aimed at the very actor
  who executes the pass). The basis, when set, is `done`, and the store
  refuses blocking done tickets, so no backward gate exists (C-3 invariant
  pass-through).
- `clearBlocker(instance) -> sprint-backlog` — ONLY C-4 clears the blocker
  (DR-8), when the finding tickets have landed; the pass re-runs from the
  checklist.

Errors: the owner absent → instances sit `in-progress` /
`owner-acceptance` — nothing waits on them (T-039). A check un-runnable in
the environment (broken deploy, dead panel) IS a finding → `fileFindings` +
`blockInstance` (the failed-pass path).

### C-5 — engine verdict pass

Reality anchor: SKILL.md tick step 3 (one read-only `reviewer` subagent per
`in-review` ticket) and `docs/design-stage-pipeline.md` §8 (the quality
gate) — explicit pass-through for the machinery. The instance-specific
contract:

- `verdictInstance(instance) -> done | blocked` — the ONLY `done` authority
  (DR-5): the instance is reviewed like any ticket, evidence floor
  included — the acceptance line "Evidence recorded as artifact on this
  ticket" is verdict-checked, not guard-enforced (AD-006): a missing
  evidence artifact FAILS the instance. PASS → `done`; FAIL → `blocked`
  with the reviewer's reasons (flow §5.3). The FAIL's basis-escalation is a
  structural no-op: the basis (when set) is `done`, and the store refuses
  blocking done tickets (DR-8) — the blocked instance surfaces to the
  owner, the resuming actor.
- Scope rule (G3/DR-5): never consulted from a parent ticket's review; a
  parent's verdict never re-opens because of an instance; the instance's
  verdict never re-opens the parent. The live check moves OUT of the
  parent's review scope INTO the instance (the U-009 precedent, flow §6).

## 3. Design patterns used

- **Shared Repository** — chosen upstream (architecture §1; AD-001):
  pass-through, not re-chosen; every contract above consumes it (all edges
  land on C-3, DR-1).
- **Template Method (data-shaped)** — the rigid mint template (AD-005): a
  fixed skeleton (the title prefix, the description's NON-BLOCKING
  paragraph, the wave acceptance criteria's four fixed lines) with exactly
  two variation points (the live-check lines, the source token). Minters
  fill slots; they never reword the skeleton. This is what makes dedup, G1
  and the owner's expectations stable.
- Nothing else forced — the mints, the pass and the verdict are plain
  function-shaped contracts; a pattern there would be weight, not design.

## 4. Data structures — the instance contract at field level

The architecture §3 carries the field values 1:1 from flow §3.1; this
section refines them to the design leg's level — the release-backfill
column, the call-level invariants, and the payload types. Nothing here
rewords the template (AD-005).

`InstanceSpec` — the mint payload, one per mint; the exact `task_create`
field set:

| Field | Pairing (R1) | Wave (R2) | Release backfill (R3 exc.) | Invariant / rationale |
|---|---|---|---|---|
| `title` | `Acceptance (live in Eclipse): <one-line live check> (<D id> AC<n>)` | `Acceptance (live in Eclipse): wave <record id> live pass (wave <record id>)` | `Acceptance (live in Eclipse): release vN.N.N live pass (release vN.N.N)` | fixed prefix + parenthesized token suffix make the title scan machine-readable (DS-004); check lines from D's ACs / the wave's agent-facing surfaces |
| `type` / `story_points` / `priority` | `task` / 0 / medium | same | same | never dispatch-relevant |
| `role` / `stage` | `tester` / `test-requirements` | same | same | immutable stage ownership (C-3); the empty division map (G2) keys on it |
| `basis` | D | `null` | `null` | the only derivation edge points INTO the instance (DR-4); G6 leans on the done basis |
| `sprint` | none | none | none | `task_plan_sprint` is never called for an instance (G1/DR-3) |
| `status` + `assignee` after mint | `in-progress` + `owner-acceptance` | same | same | matches no dispatch branch (G1); fallback landing `sprint-backlog` + `owner-acceptance` (flow §3.1); the assignee is a routing marker, never evidence (release-cadence §3.1) |
| `model` | `null` | `null` | `null` | nothing resolves or pins a model (DR-10/D-004) |
| `description` | flow §3.2 verbatim | same | same | variation points: `<pairing\|standing>`, `<source token>`, the live-check lines; the NON-BLOCKING paragraph is never reworded (AD-005) |
| `acceptance_criteria` | flow §3.3 pairing shape (the live check(s) operationalized + the evidence line) | flow §3.3 wave shape (four fixed lines) | wave shape with the release's agent-facing surfaces | the evidence line ("Evidence recorded as artifact on this ticket") is I7's verdict-checkable floor (AD-006) |

Further structures:

- `SourceToken` — grammar: `(<D id> AC<n>)` | `(wave <record id>)` |
  `(release vN.N.N)`; position: the parenthesized suffix of the title
  (AD-003). Invariant: unique per project, open OR done (I6) — identity and
  dedup key in one.
- `WaveCandidate {recordId, members[]}` — the sweep's detection
  input; predicate `isCompletedWave` (§2 C-1). Invariants: `ticker-*`
  excluded; a `paused` member keeps the wave open.
- `MintOutcome {instanceId | null, confirmed, landingZone: primary | fallback}`
  — `confirmed` is read-back truth from `task_get`, never the minter's
  intent (DS-002).
- `LiveEvidence {instanceId, kind: screenshot | panel-transcript, ref}` —
  recorded on the instance via `task_add_artifact`; the I7 floor that C-5
  verdict-checks.

## 5. Key workflows

Only the non-obvious flows; each names its components and cites the
dependency rules it upholds.

**W-1 — Pairing mint** (R1; C-1 at tick step 4; flow §5.1 STEP 4
EXTENSION):

1. The machinery's own step 4 creates D's ordinary division children.
2. `detectLiveChecks(D)` — empty → stop.
3. `derivePairingToken(D)` → `(<D id> AC<n>)`.
4. `dedupInstanceExists(token)` — true → stop (I6).
5. `mintInstance(basis = D)` → landing `in-progress` +
   `owner-acceptance`, read-back confirmed.
6. Never plan the instance into a sprint record; never spawn a worker for
   it (DR-3).

Upheld: DR-1, DR-2 (inside the per-stage loop, tied to D's division),
DR-3, DR-4, DR-10; QA-002 (idempotent under re-execution).

**W-2 — Wave sweep** (R2; C-1 at tick step 6; flow §5.1 STEP 6 EXTENSION):

1. The pass completes; the digest is written (U-061; U-108 reopen
   semantics keep the `ticker-v` record rolling).
2. `sweepCompletedWaves`: per record — `isCompletedWave` → token → dedup →
   `mintInstance(basis = null)` → landing as W-1 → one
   digest note per mint.
3. The sibling walk's cadence evaluation runs (it READS the instances the
   sweep created or kept — release-cadence §6).
4. The record closes.

The order is fixed (DR-6): the mint happens after completion is already
true — it records evidence, it never defines completion (G4); the cadence
evaluation reads what the mint created or kept (DR-9, the composition
seam). Upheld: DR-2, DR-6, DR-9; QA-002 (the sweep re-runs every pass;
dedup makes re-runs harmless).

**W-3 — Fallback and backfill mints** (C-2; I1, DR-7):

1. No ticker armed at wave close → the assistant runs the same composite as
   W-1/W-2 for the observed event, announced in chat before the write.
2. A release cut whose waves predate the rule (no covering instance, open
   or done) → `releaseBackfillMint` — one instance, `(release vN.N.N)`,
   announced; the cut sequence itself (`eclipse/releng/release.ps1`) mints
   nothing (AD-004).
3. Ticker armed → C-2 stands down; the ticker's sweep owns routine mints.

Upheld: I1, DR-7, DR-9, AD-004, AD-005.

**W-4 — Owner live pass** (C-4; flow §5.2–§5.3):

1. Open instances sit `in-progress` / `owner-acceptance` — a visible
   "awaits the owner" state on the Board.
2. ONE pass over the shared `docs/TESTING.md` checklist evidences SEVERAL
   open instances (QA-004).
3. Per instance: evidence artifact (`task_add_artifact`) → flip to
   `in-review`.
4. Each instance's verdict stays independent — C-5 judges each separately.

Upheld: DR-1, DR-3 (the G1 shape — nothing dispatches the instance), I7.

**W-5 — Verdict** (C-5; flow §5.3):

1. The next pass's step 3 spawns the read-only reviewer for each
   `in-review` instance.
2. `verdictInstance`: the acceptance criteria, evidence floor included, are
   checked (AD-006).
3. PASS → `done`; FAIL → `blocked` with the reviewer's reasons — the
   escalation to the basis is a no-op (done basis; DR-8).

Upheld: DR-5 (the only `done` authority; never a self-assessment), G3
(disjoint scope).

**W-6 — Failed pass** (C-4 + C-3; flow §5.4):

1. A check fails → `fileFindings`: `type: bug` tickets, riding the NEXT
   wave.
2. The failed evidence is recorded on the instance (artifact).
3. `blockInstance`: `blocked`, `resume_to: sprint-backlog`, the blocker
   naming the finding tickets — aimed at the owner (NEEDS-HUMAN-shaped).
4. The findings land (a later wave) → `clearBlocker` → the pass re-runs.

The verified definition (the basis, `done`) is never re-opened — the store
refuses blocking done tickets (DR-8/G6: failure flows forward).

## 6. Error handling (consolidated)

| Condition | Contract-level behavior |
|---|---|
| D's ACs carry no live-in-Eclipse check | `detectLiveChecks` → empty → the R1 path stops; a skip is not a failure |
| Doubtful live-check classification | counts as a match — mint (DS-001: a spurious instance is harmless and non-gating; a skipped pairing is never re-derived) |
| Token already present (open or done) | dedup skip — one instance per source event, ever (I6); the mint is idempotent |
| Dedup scan surface unavailable | mint deferred to a later pass — never minted blind (DS-004) |
| Record id starts `ticker-` | never a wave (ROADMAP carve-out) → skipped |
| Wave has a `paused` member | not completed — the wave stays open → skipped |
| Engine refuses the direct `in-progress` mint | retry in-pass, then the fallback landing (`sprint-backlog` + `owner-acceptance`); the assignee keeps it unclaimable (flow §3.1) |
| Update unconfirmed at pass end | repair item: re-assert the state update; NEVER re-create (dedup would skip, leaving the state wrong) (DS-002) |
| Create / store write lost mid-pass | reconnect and resume the pass (SKILL.md field observation); re-derivation is safe — every mint step is a pure function of durable state + dedup (QA-002) |
| Failed live check | `fileFindings` + failed-evidence artifact + `blockInstance` (resume `sprint-backlog`, blocker names findings); findings ride the next wave (DR-8/G6) |
| Engine FAIL on an instance | `blocked` with the reviewer's reasons; basis-escalation a no-op (done basis); the owner resumes (C-5) |
| Evidence artifact missing at verdict | the evidence-floor AC fails the instance (AD-006) — FAIL, not pass |
| Owner absent | instances sit `in-progress` / `owner-acceptance`; nothing waits (T-039 non-blocking) |
| Ticker armed while C-2 would mint | route collision — C-2 stands down; routine mints are the ticker's (I1) |
| Template deviation offered | refused — the skeleton is rigid (AD-005); flexibility lives in check content, never shape |

## 7. Dependency-rule compliance map

Every rule of the governing architecture (DR-1..DR-10) and the contract or
workflow that upholds it:

| Rule | Upheld by |
|---|---|
| DR-1 (store-only contact; no direct component edges) | every contract's edges land on C-3's `task_*` surface (§2); the mint becomes C-4's work order purely by the store holding the instance; W-1..W-6 name no component-to-component call |
| DR-2 (rides the existing tick loop; no new trigger machinery) | `pairingMint` hooks step 4, `sweepCompletedWaves` hooks step 6 (W-1/W-2); the machinery is pass-through (§1); no timer, no event subscription |
| DR-3 (instances are never dispatch inputs) | the landing zone `in-progress` + `owner-acceptance` matches no tick branch; `task_plan_sprint` never called (§4); C-1's never-clauses; the fallback landing leans on the assignee pre-check (C-3 pass-through) |
| DR-4 (nothing derives from an instance) | `InstanceSpec.stage = test-requirements` (the last stage, empty division map); `basis` only points INTO an instance; no contract reads an instance as a dispatch input |
| DR-5 (review scopes are disjoint) | `verdictInstance`'s scope rule (§2 C-5); the U-009 precedent; done is never a self-assessment (W-5) |
| DR-6 (completion computed on real tickets; fixed step-6 order) | `isCompletedWave` reads member statuses only; W-2's fixed order digest → sweep → cadence evaluation → close |
| DR-7 (the release path reads, never gates) | no release-cut mint contract exists anywhere (the negative resolution of R3's no-mint position); `releaseBackfillMint` is chat-side, never in `release.ps1` (W-3) |
| DR-8 (failure flows forward, never backward) | W-6; `blockInstance` / `clearBlocker` owned by C-4 alone; the done-basis refusal (C-3 invariant pass-through) |
| DR-9 (read-only composition across the seam) | W-2 steps 2–3: the sweep mints first, the cadence evaluation second (reads instances, comments outcomes); no contract writes cadence state; neither governing doc modified |
| DR-10 (no committed provider/model ids) | `InstanceSpec.model = null`; §4 introduces no id anywhere |

## 8. Traceability matrix (T-119 AC-2)

Every element of the governing architecture → its design resolution here:

| Architecture element (T-107) | Design resolution |
|---|---|
| C-1 mint extensions | §2 C-1: `detectLiveChecks`, `derivePairingToken`, `dedupInstanceExists`, `mintInstance`, `pairingMint` (R1), `sweepCompletedWaves` (R2), `isCompletedWave` |
| C-2 kickoff assistant fallback | §2 C-2: `fallbackMint`, `releaseBackfillMint`; the owner-prerogative note |
| C-3 task store | explicit pass-through — substrate; engine-owned contracts; consumed surfaces named (§2 C-3) |
| C-4 owner live pass | §2 C-4: `executeLivePass`, `fileFindings`, `blockInstance`, `clearBlocker` |
| C-5 engine verdict pass | §2 C-5: `verdictInstance` + the scope rule; machinery pass-through (SKILL.md step 3, design-stage-pipeline §8) |
| R1 — pairing division at tick step 4 | `pairingMint` (§2 C-1); W-1; token `(<D id> AC<n>)` (§4) |
| R2 — wave-completion sweep at tick step 6 | `sweepCompletedWaves` + `isCompletedWave` (§2 C-1); W-2; token `(wave <record id>)` (§4) |
| R3 — no mint at the cut; backfill exception only | the negative resolution — no component carries a release-cut mint contract; `releaseBackfillMint` (§2 C-2), W-3, token `(release vN.N.N)` (§4); DR-7 row (§7) |
| Actor row 1 — stage ticker (only routine minter; never spawns/plans/accepts) | `pairingMint` / `sweepCompletedWaves` never-clauses (§2 C-1) + DR-3/DR-4 (§7) + the acceptance monopoly (C-5, DR-5) |
| Actor row 2 — kickoff assistant (fallback, same template, announced; never ad-hoc) | §2 C-2 (both contracts; the announcement requirement; the template-refusal error) |
| Actor row 3 — product owner (executes the pass, files findings, may override) | §2 C-4 (four contracts) + the prerogative note (outside every contract surface) |
| Actor row 4 — engine review pass (only `done` authority) | §2 C-5 (`verdictInstance`; W-5) |
| Pseudo-actor `owner-acceptance` | §4 `status` + `assignee` row: a routing marker ("awaits the owner"), never evidence (release-cadence §3.1, pass-through) |
| G1 — never dispatched | DR-3 row (§7); the landing-zone design (§4); the fallback-landing error row (§6) |
| G2 — nothing derives from an instance | DR-4 row (§7); the `stage` / `basis` rows (§4) |
| G3 — parent verdicts unaffected | C-5's scope rule (§2); DR-5 row (§7) |
| G4 — wave completion unaffected | `isCompletedWave` reads members only; W-2's fixed order; DR-6 row (§7) |
| G5 — release unaffected | DR-7 row (§7); open instances carry over — no contract reads them at the cut |
| G6 — failure isolation | W-6; `blockInstance` / `clearBlocker` (§2 C-4); the done-basis refusal (§2 C-3); DR-8 row (§7) |
| The minted-ticket data contract (arch. §3) | §4 — the field table (values 1:1, plus the release column and call-level invariants), `SourceToken`, `InstanceSpec`, `WaveCandidate`, `MintOutcome`, `LiveEvidence` |
| Convergence / idempotence (arch. QA-002) | DS-002 + DS-004; the §6 lost-write and unconfirmed-update rows |
| Auditability (arch. QA-003) | DS-003 — digest notes for R2 mints; store history events for every mint |
| Owner economy (arch. QA-004) | W-4 step 2 (one pass, several instances); wave-level minting — one instance per wave |
| Composition seam (arch. §7) | W-2 (fixed order, mint first) + the DR-9 row (§7); the cadence flow is read-only here — `docs/release-cadence-flow.md` unmodified |

Consistency with the system flow (T-119 AC-2's "no contract contradicts"):
every mint value is carried 1:1 from flow §3.1 (§4), the mint composites
apply the flow's §5.1 wording step-for-step (I1), the failed-pass behavior
is flow §5.4 (W-6), and G1..G6 resolve to the same machinery branches flow
§6 names — nothing here rewords the template (AD-005), invents a store
concept, or adds a trigger.

## 9. Acceptance criteria mapping (T-119)

- **AC-1** (design artifact exists, docs/ convention About header, component
  contracts with inputs/outputs/error behavior named, workflows consistent
  with DR-1..DR-10): this document — §2 contracts in
  `name(inputs) -> output | errors` form, §4 data structures, §5 workflows
  W-1..W-6 with their DR citations, §6 error behavior, §7 the explicit
  DR-compliance map.
- **AC-2** (every architecture element resolves to a contract or an
  explicit pass-through; no contradiction with
  `docs/live-in-eclipse-recurrence-flow.md`; composition with
  `docs/release-cadence-flow.md` preserved): §8 traceability matrix
  (C-1..C-5, R1/R2/R3, the four actor rows + the pseudo-actor, G1..G6, the
  minted-ticket data contract — each resolved or explicitly passed
  through), with the flow-consistency citations closing §8 and the
  release-cadence seam preserved read-only (W-2, DR-9).
- **AC-3** (artifact recorded on the ticket before in-review; done is never
  self-assessed): store discipline — `task_add_artifact` (kind `doc`)
  written before the `in-review` status update; `done` is the engine's
  verdict (C-5's monopoly — this ticket's own instance of DR-5).

## 10. Lane and non-goals

This ticket's lane is one new doc file
(`docs/live-in-eclipse-recurrence-design.md`). Not touched:
`docs/live-in-eclipse-recurrence-architecture.md`,
`docs/live-in-eclipse-recurrence-flow.md`, `docs/release-cadence-flow.md`,
`docs/design-stage-pipeline.md`, `.opencode/skills/stage-pipeline/`,
`AGENTS.md`, `docs/TESTING.md`, any code, any store data. No concrete
provider or model ids anywhere (DR-10/D-004). The T-040 one-time transition
stays the store motion the architecture records (its §8) — no contract here
claims it. The dedup scan stays specified, not scripted; a checking script
is its own ticket with its own tests. Realization here is wording- and
store-motion-level, carried by the existing surfaces; where the pipeline
divides an implementation child, it derives from §2/§4, and the contracts'
verification belongs to `test-software-design` (component criteria against
§2 and §6).
