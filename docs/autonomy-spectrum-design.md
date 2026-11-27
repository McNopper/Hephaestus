# Design: the autonomy-spectrum flow's component contracts (T-125)

## About this document

- **Kind:** `doc` / design-stage artifact of ticket **T-125** (basis T-113, stage `design`): the design INSIDE the autonomy-spectrum
  flow's five components — per-component contracts in
  `name(inputs) -> output | errors` form, the data structures the
  components consume (the dial record, the kickoff horizon read, the
  acceptance-recurrence ticket, the release boundary state), the four key
  workflows (intake writes the position; kickoff obeys the horizon; wave
  close gates and releases; the safe-end default read), and the error
  behavior at every contract face. The component set C1–C5, the
  default-deny edges E1–E6 with their seven denied edges, the guarantee
  flows G-1–G-4, the quality attributes QA-1–QA-4 and the architecture
  decisions AD-1–AD-6 are the architecture's fixed structure ([ARCH]);
  this doc adds no component and no edge.
- **Read by:** the downstream implementation stage of this V-walk (its
  basis — what gets landed into the real surfaces per component), the
  paired `test-design` stage (whose criteria decide this artifact), the
  engine review pass (the only `done` authority), and the maintainer.
- **Related:** `docs/autonomy-spectrum-architecture.md` (T-113's product —
  the governing definition; cited [ARCH]); `docs/autonomy-spectrum-system.md`
  (the upstream basis; cited [SYS]); `docs/design-stage-pipeline.md` (the
  unchanged tick algorithm, §4; cited [DSG]); `docs/acceptance-run-architecture.md`
  and `docs/acceptance-run-design.md` (the reused machinery's architecture
  and inside-design — C2 ≡ [AR] M1, the tick ≡ [AR] M2, the verdict ≡
  [AR] M4; cited [AR] / [AR-D]); `.opencode/agent/product-owner.md` (the
  intake actor's procedure; cited [PO]); `AGENTS.md` "The autonomy
  spectrum (the operating model, owner requirement 2026-11-15)" (the
  doctrine; cited [AGENTS]). Design decisions are **DD-01..DD-06**, data
  structures **DS-1..DS-4**, workflows **W-1..W-4** (doc-local ids).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records and the product-backlog /
  sprint-backlog statuses are gone; the store is the
  seven-state model (born ready/paused, claims ready-only). The plan/close
  steps and sprint records narrated below are historical.

## 1. Design scope

The spectrum is a process/agent flow: its components are components of
the run, each anchored to a real surface of this repo — an agent
definition, a store file, a schedule config, a skill, a doctrine document
([ARCH §Scope]). This doc designs INSIDE each component: the contracts,
the data they consume, the workflows that compose them, the error faces.
The composition rule follows the reuse decision ([ARCH] AD-1): C2 ≡ [AR]
M1, the pump's control layer ≡ [AR] M2, the gates' verdict lane ≡ [AR]
M4 — their contracts are [AR-D]'s, consumed via the pass-through record
(§7) and never re-specified. What this doc actually designs is exactly
what the spectrum contributes over that reused machinery:

- the **intake actor's contracts** (C1 — the only new actor);
- the **dial record as data** — its grammar, custody and read path
  (DS-1, DS-2) — the position's only home;
- the **kickoff horizon read** and the **schedule lane** (C3's two dial
  touchpoints, and the only ones);
- the **release boundary** (C4's human-only edge) and the
  **acceptance-recurrence data path** (C5 doctrine landing in C2).

Every workflow below names its executing component and the edge it rides
— no orphan steps, no unnamed executors, no denied edge used anywhere
(§5, §8).

## 2. Component contracts

Contract notation throughout: `name(inputs) -> output | errors`. Every
component carries at least one contract with all three faces (inputs,
outputs, error behavior).

### 2.1 C1 `intake` — the actor that writes the position

Reality anchor: `.opencode/agent/product-owner.md` "The two dials (goal
intake)" ([PO]; [ARCH §Interfaces to the real code C1]). The seat is
write-denied for repo files and holds only the store verbs (E1) plus the
doctrine load (E5).

- **C-C1-1 `classify(ownerMessage) -> goalRegistration | ticketRegistration | answer`**
  — the intake classification ([PO] loop step 1). Inputs: the owner's
  message. Outputs: a GOAL root (`stage: requirements`) for a whole objective, a
  staged ticket (requirement or `type: bug`) otherwise, or an
  evidence-backed answer for a question. Errors: ambiguous input →
  clarification round-trips to the owner, **bounded at two** ([PO]'s
  bound; [SYS §F-1] "a couple" made concrete) — past the bound the
  registration proceeds with the open questions recorded on the ticket,
  never resolved by a private guess; an unknown `basis` id →
  the registration **halts** (no orphan ticket), the id returns to the
  owner for correction ([PO] step 3).
- **C-C1-2 `recordPosition(goalId, answers | openQuestions) -> DialRecord`**
  — the position write (E1 `task_create` / `task_update`; [SYS
  §Interfaces 1]). Inputs: the GOAL root's id; the dial answers or the
  open questions. Outputs: the DS-1 record in the GOAL root's
  description (a `task_add_comment` may carry the intake Q&A beside it).
  **The write fires even when a dial answer is missing** — the open
  question is recorded with the registration ([ARCH] E1; [SYS §F-4]).
  Errors: a store call failure → surface the failure with the draft text
  preserved in the reply and retry from that draft; the seat holds no
  state outside the store, no local queue ([PO] step 4); the record is
  C1's ONLY spectrum write — never a restatement on a child ticket,
  never another surface (the side-channel denial, DENY-7).
- **C-C1-3 `askDials() -> answers | openQuestions`** — the bounded ask
  (E5 loads the two-dials procedure). Inputs: the owner's stated
  position, if any. Outputs: answered dials, or the open questions that
  ride into C-C1-2 when the owner stays silent. Errors: the round-trip
  bound (two) exhausted → openQuestions, never a stall, never a
  registration refusal — the safe end applies at read time (W-4).

**No-edge boundaries (contract-visible):** C1 never steers the pump — no
launch, no stop signal, no mid-run message (DENY-1); C1 never verdicts
and never releases — it writes a record, nothing else (DENY-2). There is
no C1 contract whose inputs or outputs mention C3 or C4; the position
travels only through the C2 record and is read only at kickoff (W-2).

### 2.2 C2 `store` — the substrate (text custody of the position)

Reality anchor: `.harness/tasks/hephaestus/<GOAL-id>.md` + the `task_*`
verb surface; git-versioned Markdown (D-006) ([ARCH §Interfaces to the
real code C2]).

- **C-C2-1 `holdDialRecord(goalId, record) -> Ticket` / `readDialRecord(goalId) -> text`**
  — the position's custody (E1 write / E2 read). C2 gives **text custody
  only** ([ARCH] C2 row; [SYS §Major Parts 2]): the record is stored and
  returned verbatim as labeled ticket text — no dial schema, no dial
  validation, no dial-driven store behavior (AD-3). Errors: **none at
  the dial level** — an absent or malformed record is not a store error;
  the read returns what is there and the kickoff read (C-C3-1) owns the
  interpretation; an unknown id → not-found (the verb's own error).
  Provenance is git-auditable: `task_get` verifies the record, `git log`
  on the ticket file shows who wrote it and when (D-006; [SYS
  §Interfaces 3]).
- **C-C2-2 everything else — consumed, not re-specified** (AD-1): the
  ticket mutation guard, atomic claim, readiness, append-only history,
  sprint lifecycle, artifact index and derivation links are [AR-D §2.1]
  contracts C-M1-1..C-M1-7, unchanged. The spectrum adds no verb, no
  field, no store behavior (the negative sweep, §7).

**No-edge boundaries (contract-visible):** C2 has **zero outgoing
edges** — no callbacks, no notifications; discovery is by read at the
tick boundary (DENY-4). Every component's store touch is a verb call;
hand edits are not an architecture edge (DENY-6).

### 2.3 C3 `pump` — the control layer (the two dial touchpoints)

Reality anchor: one steward session per stage on `experimental.automations` (renamed from schedules in 2.3.1),
armed/disarmed by `ticker-setup.ps1`; the `stage-pipeline` skill ([ARCH
§Interfaces to the real code C3]). The tick algorithm is [DSG §4]
verbatim ([AR-D] C-M2-1 consumed).

- **C-C3-1 `readHorizon(goalRoot) -> horizon | defaults`** — the kickoff
  read (E2 `task_get`/`task_board` on the goal root; [SYS §Interfaces 2]).
  Inputs: the run's goal root resolved per DS-2's walk. Outputs: the
  horizon mode from the record's `goal horizon` line — and **only that
  line**: the cadence line of DS-1 is outside this contract's surface
  entirely (DD-03; QA-3). Errors: record absent, unparseable, or no
  chain root → fall through to the doctrine defaults (C-C5-1;
  [SYS §F-4]) — **never a question back to the owner** (the pump has no
  C1 edge, DENY-1) and **never a stall**; a store read failure → the
  same fallthrough, failing safe toward the safe end.
- **C-C3-2 `tick(stage) -> Digest`** — the unchanged five-step tick
  (E2 + E4): SCAN → BLOCKED → DONE → READY → CLOSE, [DSG §4] order,
  escalation-before-launch ([AR-D] C-M2-1/C-M2-4 consumed; the
  `task_set_blocked` escalation rides E2 onto the basis, NEEDS-HUMAN as
  the last resort). **No dial parameter exists in this signature** — the
  tick is identical under both horizon modes (QA-3; [SYS §Major Parts 3]).
  Errors: per-step degradation only, as consumed ([AR-D §2.2]): an empty
  READY set costs one thin readiness query and closes; a claim lost to
  contention skips that ticket to the next tick.
- **C-C3-3 `enforceHorizon(horizon) -> scheduleAction`** — the schedule
  lane (E6; not a store edge). Inputs: the kickoff read result
  (C-C3-1). Outputs: `run-to-Product-Goal` → the stage schedules recur;
  `time-boxed(end)` → the steward disarms the schedules at the horizon
  end. The disarm is a **deliberate stop, not an escalation** ([SYS
  §Interfaces 5]): the pump finishes the current tick boundary cleanly —
  claimed workers run to completion, engine verdicts land, the sprint
  closes with a digest naming the horizon end — and launches no new
  READY tickets ([SYS §F-2]). In-flight tickets are **never abandoned
  mid-flight because a clock ran out** (AD-4). Errors: a missed disarm
  (steward session died) → the next boundary disarms; horizon precision
  is schedule-tick-granular (AD-4's recorded trade-off) and the digest
  records the actual disarm time — a late disarm is visible, never
  silent.

**No-edge boundaries (contract-visible):** the cadence dial has **no
pump edge at all** — no C3 contract reads it ([ARCH] C3 row; QA-3);
C3 never accepts — `done` is engine-only (DENY-3); C3 never releases
(DENY-3/DENY-5). C3's store touch is exactly E2's verb list; its
knowledge touch is exactly E4 (the `stage-pipeline` skill with the
division map).

### 2.4 C4 `gates` — governance (verdicts plus the release boundary)

Reality anchor: the engine review pass + the project quality gate +
`eclipse/releng/release.ps1` reachable only from the human-review
context ([ARCH §Interfaces to the real code C4]).

- **C-C4-1 `review(ticket) -> Verdict`** — the verdict lane (E3):
  consumed, not re-specified (AD-1) — [AR-D §2.4] C-M4-1..C-M4-3: engine
  verdicts gate every ticket's `done`, FAIL blocks in the ticket's own
  stage, UNCLEAR round-trips a doubt. This is the lane the
  acceptance-recurrence ticket's eventual review also walks (DS-3) —
  acceptance hardening, never a release input ([SYS §F-3]).
- **C-C4-2 `releaseBoundary(waveEvidence) -> released | pending`** — the
  release decision's shape. Inputs: the wave's evidence read via E3
  (`task_get`/`task_traceability`: all tickets done with engine
  verdicts) plus the wave-close fact (commit + push landed). Outputs:
  `released` — `release.ps1` runs (tag per D-002; the `-SNAPSHOT` bump on
  demand, `-BumpNext`) —
  **only in the human-review context**, on a human review that accepts;
  `pending` — without such a review, and pending is an **absence, not a
  datum** (DD-04, DS-4): no store field, no timer, no machine state, no
  retry. Errors: invocation from any machine context is **structurally
  unreachable** (DENY-5 — no component holds an edge to `release.ps1`;
  AD-5): the error face is unreachability, not a runtime refusal — there
  is nothing to refuse with, because no machine actor's surface contains
  the release path.

**No-edge boundaries (contract-visible):** the verdict never commands
the pump — it steers only through C2 state (division triggers, readiness
flips; DENY-3). C1's writes are invisible to C4's release decision
(DENY-2): no dial value is a release input at any cadence setting — the
floor holds everywhere (QA-4).

### 2.5 C5 `doctrine` — the static knowledge layer

Reality anchor: the document surfaces themselves — [AGENTS] spectrum
rules, ROADMAP standing rules (T-041, T-039), [PO], [DSG §4] ([ARCH
§Interfaces to the real code C5]). Data, not an active process; process
change = editing the doctrine once, every run inherits.

- **C-C5-1 `dialDefaults() -> {horizon, cadence}`** — the safe end as
  doctrine data (AD-6): `horizon = run-to-Product-Goal` (the general
  rule), `cadence = releases-only-on-human-review` (the T-041 safe end).
  Consumed by C-C3-1's fallthrough (W-4) and by C1's ask-then-default
  behavior (E5). Errors: none — static data.
- **C-C5-2 `twoDialsProcedure() -> {vocabulary, askBound, defaults}`** —
  E5's load for C1: the dial vocabulary ([SYS §The two dials]), the
  round-trip bound (two, [PO]), the safe-end defaults.
- **C-C5-3 `divisionMap(stage) -> entries`** — E4's load for C3: the
  fixed per-stage division data, including the **T-039 recurrence
  entry** — the acceptance-recurrence rule carried as DATA ([AGENTS]:
  "the stage-pipeline skill's division map carries the recurrence"), so
  the tick algorithm stays unchanged (QA-3) while every successful
  wave/release grows its acceptance ticket (DS-3, W-3).

**No-edge boundaries (contract-visible):** C5 has zero outgoing edges —
it is loaded, never executes ([ARCH §Dependency rules] direction rule).

## 3. Design patterns used

- **Blackboard — consumed (AD-2, [AR])**: the store as the single shared
  substrate with independent actors around it; chosen upstream, inherited
  by every contract here. Not re-chosen.
- **Template Method — consumed ([DSG §4])**: the tick's invariant
  five-step skeleton with per-stage data hooks; C-C3-2 is its named
  form. The spectrum's dial data changes no step — the pattern is why
  pump invariance (QA-3) is structural.
- **Facade — the `task_*` verb surface**: every actor programs against
  the verbs, never the store files (the write discipline as an
  interface; DENY-6).
- **None introduced beyond these.** The dial record is a plain
  labeled-text convention (DS-1) — fixed grammar, no parser machinery,
  no new pattern; adding one would be weight, not design ([SYS
  §Overview] "they add no machinery").

## 4. Data structures

### DS-1 · The dial record (the position's grammar and custody)

The record is a labeled block of ticket text living in the **GOAL
root's description** — the position's ONLY home ([SYS §Major Parts 2];
a `task_add_comment` may carry the intake Q&A beside it, E1). Grammar
(fixed labels, closed vocabularies — DD-01):

```text
run position (autonomy spectrum · set at goal intake · [ARCH] E1)
- goal horizon:   run-to-Product-Goal | time-boxed (ends <date time>)
- review cadence: releases-only-on-human-review | <a tighter
                  human-entry setting agreed with the owner>
- open questions: none | <each unanswered dial, one per line>
```

Grammar rules and invariants:

- **Only what varies is recorded.** The T-041 floor and the T-039
  recurrence are standing rules, not dial values ([SYS §The two dials]
  "T-041 is a standing rule, not a dial value"; [SYS §F-3] the
  recurrence rides "at every cadence setting") — they live in C5
  doctrine and are never fields of the record; recording them would
  imply they are tunable.
- **`time-boxed` carries its end** — the value the steward's disarm
  (C-C3-3) enforces at the schedule lane (AD-4).
- **Custody:** written only by C1 (E1), read only at kickoff by C3 (E2);
  child tickets inherit through their `basis` chain — never restated per
  child; no side channel may carry it — not chat memory, not session
  state, not a config file (DENY-7). Exactly one home; verification
  resolves to the store (`task_get`) and provenance to git (`git log`,
  D-006) — QA-2.
- **C2 validates nothing** (AD-3): presence and shape are C1's duty at
  write time and C-C3-1's to interpret at read time; the store returns
  text, the contract's error face is not-found only.

### DS-2 · The kickoff horizon read (resolution walk + fallthrough)

How any stage ticker resolves "the run position" for its stage (the
read side of DS-1; consumed by C-C3-1 at every tick's kickoff):

1. **Resolve the root:** from the stage's tickets, walk the `basis` chain
   to its root — the GOAL root (`stage: requirements`; DD-05; [PO]
   step 1 registers whole objectives as requirements-stage roots). A staged
   ticket with no chain root has no owner goal → step 3.
2. **Read and parse:** `task_get` the root; if its description carries
   a DS-1 record that parses against the grammar → the horizon (and
   end, when time-boxed). Only the `goal horizon` line is consumed
   (DD-03).
3. **Fall through:** record absent, unparseable, no root, or the read
   failing → C-C5-1's defaults (`run-to-Product-Goal`, the T-041 floor
   unchanged) — the run proceeds (W-4; [SYS §F-4]). The fallthrough is
   silent-by-design at the pump: the position stays store-decidable
   because the record names its open questions and the doctrine names
   the defaults.

The read happens at **every kickoff** — no cached copy in any session
(DD-02; QA-2 "never memory-resident"): a mid-run re-position by the
owner (C1 updates the record via E1) is obeyed at the next tick
boundary, with no restart machinery (QA-1: advisory data).

### DS-3 · The acceptance-recurrence ticket (T-039 as data)

The fresh `test-requirements` acceptance ticket the division map's
recurrence entry (C-C5-3) lands in C2 at the wave-close boundary — one
per successful wave/release ([SYS §F-3]; [ARCH] G-3):

- Shape: an ordinary staged ticket (`stage: test-requirements`), created
  through the division/link verbs at the boundary — whichever actor's
  division map fires it, the write goes through the store ([ARCH
  §System part → component mapping, cross-cutting]) — the architecture
  fixes the channel and the non-blocking property, not the firing hand.
- **Non-blocking by structure:** a derivation LEAF — no ticket's
  `basis` points at it, no readiness row references it, so no component
  waits on it; it walks its own `in-review → done` on the ordinary
  verdict lane (C-C4-1) and never gates the wave that spawned it.
- Idempotency: "one per wave/release" heals like any division — at the
  next boundary the firing actor re-reads existing derivations and
  creates only the missing ticket (the same heal discipline [AR-D §2.2
  C-M2-3] uses; consumed, not re-invented).

### DS-4 · The release boundary state (pending-by-absence)

There is **no "release pending" datum** (DD-04, AD-5): the wave's
release-readiness is read from existing evidence (all tickets done with
engine verdicts — E3 `task_traceability`; commit + push landed — the
wave-close fact), and the human acceptance is evidenced by the release
artifact itself (the tag + the release commit citing the wave), cut in
the human-review context. Nothing to clean up, nothing to time out: the
wave, not the calendar, is the release cadence ([SYS §Technology
choices]).

## 5. Key workflows

Every step names its executing component and the edge it rides. The
four workflows are one-to-one with [SYS §End-to-end data flows]
F-1..F-4, exactly as [ARCH §Guarantee flows] traces them to G-1..G-4.

### W-1 · Goal intake writes the position (F-1 → G-1; executor: C1)

1. **Classify** (C-C1-1) — the owner's message is a whole objective → a
   GOAL root (`stage: requirements`); ambiguous → bounded clarification
   (two round-trips, [PO]).
2. **Ask** (C-C1-3, E5) — the dials when the owner leaves them
   unstated; silence → openQuestions, never a block.
3. **Record** (C-C1-2, E1) — the DS-1 record into the GOAL root's
   description (`task_create`/`task_update`), firing even with open
   questions; the intake Q&A may sit in a comment (E1
   `task_add_comment`).
4. **Cite and verify** ([PO] steps 6–7, E1) — the ticket id cited back
   in the same reply; `task_get` re-read confirms the record (the
   store, not memory, is the proof).

Evidence ([ARCH] G-1 row): `task_get` on the GOAL ticket shows the
labeled record; `git log` on the ticket file shows provenance; child
tickets carry no restatement.

### W-2 · Kickoff obeys the horizon (F-2; executor: C3; edges E2, E4, E6)

1. **Arm** (C3 steward, E6) — the steward arms the stage schedules at
   kickoff (`ticker-setup.ps1`).
2. **Read** (C-C3-1, E2) — at every tick's kickoff, resolve the goal
   root (DS-2) and read the horizon; no record → defaults (W-4).
3. **Tick** (C-C3-2, E2 + E4) — the five-step tick, [DSG §4] verbatim,
   identical under both horizon modes (QA-3).
4. **Enforce** (C-C3-3, E6) — `run-to-Product-Goal` → schedules recur;
   the run stops only at NEEDS-HUMAN escalations or when the GOAL root
   itself reports done by engine verdict ([SYS §F-2]). `time-boxed` →
   the steward disarms at the horizon end: the current tick boundary
   finishes cleanly, in-flight tickets run to their engine verdicts,
   the sprint closes with a digest naming the horizon end, no new
   READY launches. **A deliberate stop, not an escalation** ([SYS
   §Interfaces 5]).

### W-3 · Wave close, the gates and the release boundary (F-3 → G-2, G-3; executors: C3, C4)

1. **Close** (C3, E2) — the wave completes with engine verdicts and
   **always ends at commit + push** ([ARCH] G-2 row); worker lanes
   landed.
2. **Recur** (C3 firing the C5 division-map entry, E2 + E4; lands in
   C2, the E3-observable) — a fresh DS-3 acceptance ticket, one per
   successful wave/release, non-blocking by structure.
3. **Verdict** (C4, E3) — engine verdicts gate every ticket's `done`
   (C-C4-1, consumed); the acceptance ticket's eventual review is
   hardening, never a release input.
4. **Release boundary** (C-C4-2) — without a human review that accepts:
   `pending`, an absence (DS-4), the wave ends at commit + push and
   nothing else happens — no timer, no machine retry. With one:
   `release.ps1` runs **in the human-review context only** (tag per
   D-002; the `-SNAPSHOT` bump on demand, `-BumpNext`). No dial setting can subtract the human
   from the release ([ARCH] G-2; QA-4).

### W-4 · The safe-end default read (F-4 → G-4; executors: C1, C3; edges E1, E2, E5)

1. **Register anyway** (C-C1-2, E1) — the open question is recorded
   with the registration; E1 fires regardless.
2. **Fall through** (C-C3-1, E2) — the kickoff read finds absent or
   unresolved dial state → C-C5-1's defaults: the run-to-Product-Goal
   horizon with the T-041 cadence floor.
3. **Proceed dial-free** — no claim, no verdict, no wave close, no
   commit consumes a dial value as a precondition ([SYS §F-4]); the
   position steers a run and can never stall one (QA-1).
4. **Resolve later** (C-C1-2, E1) — a later owner answer updates the
   record; the next kickoff (W-2 step 2) obeys it. No restart
   machinery exists or is needed.

## 6. Error handling (consolidated)

| Condition | Contract-level behavior |
|---|---|
| Owner leaves the dials unstated | bounded ask (two round-trips, [PO]); register with open questions recorded (C-C1-2); the safe end applies at read time (W-4) — never a stall |
| Owner answers after registration | C1 updates the record (E1); the next kickoff obeys (DS-2); no pump restart |
| Store call fails during intake | surface the failure with the draft text preserved; retry from the draft ([PO] step 4); no local queue — the seat holds no state outside the store |
| Unknown `basis` id at registration | the registration halts — no orphan ticket; the id returns to the owner ([PO] step 3) |
| Dial record absent or unparseable at kickoff | fall through to the doctrine defaults (C-C3-1, DS-2 step 3); the tick proceeds; the pump never asks the owner (no C1 edge) |
| Store read failure at kickoff | same fallthrough — fail safe toward the safe end; the tick proceeds |
| Time-boxed horizon reached mid-tick | finish the boundary cleanly: in-flight to engine verdicts, sprint closes with a digest naming the horizon end, no new launches (C-C3-3) |
| Disarm missed (steward session died) | the next boundary disarms; the digest records the actual disarm time — visible, never silent; precision is schedule-tick-granular (AD-4) |
| No human acceptance after a wave | the release stays pending — an absence, not a datum (DS-4): no timer, no machine retry, no state to clean up (AD-5) |
| Release path invoked from a machine context | structurally unreachable (DENY-5): no machine actor's surface contains `release.ps1` |
| Wave close interrupted mid-division (recurrence ticket) | division heal at the next boundary: re-read existing derivations, create only the missing DS-3 ticket (the [AR-D] heal discipline, consumed) |
| Dial record found on a child ticket (side-channel violation) | not a readable position — the walk reads the goal root only (DS-2); the violation is a review-visible defect against DENY-7, never an input |

## 7. Pass-through record: where [ARCH], [AR-D] and [DSG] fix the structure

Positive mappings (consumed, not re-decided):

| Upstream fixes | Consumed here as | (No re-decision) |
|---|---|---|
| [ARCH] C1..C5 set, E1..E6 + the seven denials | §2's component set, §8's compliance map | no new component, no new edge |
| [ARCH] AD-1 reuse (C2 ≡ [AR] M1, control ≡ M2, verdict ≡ M4) | §7 rows below; C-C2-2, C-C3-2, C-C4-1 | the reused contracts are cited, never re-specified |
| [AR-D §2.1] store contracts (C-M1-1..C-M1-7) | C-C2-2 | no verb, field or store behavior added |
| [AR-D §2.2] tick/admit/divide/escalate/close (C-M2-1..C-M2-5) | C-C3-2 | the tick is [DSG §4] verbatim; no dial parameter |
| [AR-D §2.4] verdict contracts (C-M4-1..C-M4-3) | C-C4-1 | engine-only `done`; FAIL-block; UNCLEAR doubt — unchanged |
| [AR-D §2.2 C-M2-3] division heal | DS-3 idempotency | same discipline, consumed |
| [DSG §4] tick algorithm + worker fulfillment ([DSG §5], [AR-D §2.3]) | W-2 step 3; unchanged worker machinery | the spectrum adds nothing to workers |
| [PO] loop steps 1–9 (bounds, store-failure draft retry, cite-back, verify) | C-C1-1..C-C1-3, W-1 | the round-trip bound is [PO]'s two, restated not re-decided |
| [AGENTS] standing rules T-041, T-039; [SYS] safe-end defaults | C-C5-1..C-C5-3 (AD-6) | defaults are inherited doctrine, not new policy |

Negative sweep (re-decision markers checked and **absent**): no new
store schema field (the record is ticket text, AD-3); no new verb; no
dial validation in the store; no changed or extended tick step (W-2
step 3 is the five-step order verbatim); no pump parameter for the
cadence dial (DD-03); no cached run position in any session (DD-02); no
restatement of the position on child tickets; no machine release path
(DENY-5); no second `done` authority; no timer or retry machinery
behind `pending` (DD-04); no new pattern beyond §3's consumed three.

## 8. Dependency-rule compliance map (E1–E6 and the seven denials)

Every edge and denial of [ARCH §Dependency rules] against the contracts
and workflow steps that ride or avoid it:

| Edge / denial | Upheld by |
|---|---|
| **E1** C1 → C2 (`task_create` the GOAL root, `task_update` the record, `task_add_comment` the Q&A) | W-1 steps 3–4; C-C1-2 — C1's every store touch is on this verb list |
| **E2** C3 → C2 (kickoff read `task_get`/`task_board` + `task_readiness`; tick verbs `task_plan_sprint`/`task_claim`/division/`task_close_sprint`; `task_set_blocked` escalation) | W-2 steps 2–3; C-C3-1/C-C3-2 — the ticker's dial read and tick verbs, nothing outside the list |
| **E3** C4 → C2 (`task_get`/`task_traceability` reads; engine-only `done`; the acceptance ticket's landing as the E3-observable) | W-3 steps 3–4; C-C4-1/C-C4-2 |
| **E4** C3 → C5 (the `stage-pipeline` skill: tick procedure + division map incl. the T-039 recurrence entry) | W-2 step 3; W-3 step 2; C-C5-3 — the recurrence is data C3 already loads |
| **E5** C1 → C5 (the two-dials procedure: vocabulary, bound, defaults) | C-C1-3; W-1 step 2; C-C5-2 |
| **E6** C3 → stage schedules (arm at kickoff, disarm at a time-boxed horizon; not a store edge) | W-2 steps 1 and 4; C-C3-3 — the horizon's enforcement point |
| **DENY-1** C1 → C3 | §2.1 boundary block: no C1 contract mentions the pump; the position travels only through C2, read only at kickoff (W-2 step 2) |
| **DENY-2** C1 → C4 | §2.1: C1 writes a record, nothing else — no verdict or release verb in its surface |
| **DENY-3** C3 ↔ C4 (both directions) | §2.3/§2.4 boundary blocks: the ticker never accepts, the verdict steers only through C2 state |
| **DENY-4** C2 → anything | §2.2: zero outgoing edges — no callbacks, no notifications; discovery by read at the tick boundary |
| **DENY-5** any machine component → the release trigger | C-C4-2's unreachable error face; W-3 step 4 — `release.ps1` exists in no machine surface (T-041 as structure, AD-5) |
| **DENY-6** any component → C2 files directly | §2.2 + §3 Facade: every store touch is a verb call; hand edits are not an edge |
| **DENY-7** any side channel carrying the run position | DS-1 custody invariants; DS-2 reads the goal root only; §6's last row treats a restated record as a defect, never an input |

**The C1/C3/C4 no-edge rule, contract-visible.** The architecture's
named structural rule — no edge between any pair of C1, C3, C4 ([ARCH
§Dependency rules], AD-2) — is enforced by contract surface, not
convention: C1's contracts (§2.1) name neither C3 nor C4 in any
signature; C3's contracts (§2.3) carry no intake and no verdict input —
the only dial input is the kickoff read of C2 data; C4's contracts
(§2.4) consume store state only. Every workflow step in §5 rides E1,
E2, E3, E4, E5 or E6 — no step links C1, C3 or C4 directly; every
interaction among them is store-mediated.

## 9. Quality attributes and architecture decisions: realization map

Quality attributes ([ARCH §Key quality attributes]) — the design
mechanism that realizes each:

- **QA-1 Non-blocking** — no contract in §2 consumes a dial value as a
  precondition: the only dial consumers are C-C3-1's advisory read and
  C-C3-3's schedule action; every claim, verdict, wave-close and commit
  path is dial-free. Every error face falls toward "proceed" (§6). The
  only unplanned stop is NEEDS-HUMAN; the time-boxed horizon is
  deliberate, not unplanned.
- **QA-2 Verifiability** — DS-1 single-home custody + the DS-2 walk that
  reads only the root; DD-02 re-reads at every kickoff so no memory
  copy exists; D-006 makes provenance `git log`-decidable.
- **QA-3 Pump invariance** — C-C3-2 is the [DSG §4] signature with **no
  dial parameter**; DD-03 restricts the kickoff read to the horizon
  line; the cadence dial appears in no C3 contract (its effects live in
  C4's release boundary and the C5 recurrence data).
- **QA-4 Safety floor** — DENY-2 and DENY-5 as contract absences (no
  verdict verb in C1; no release path in any machine surface);
  NEEDS-HUMAN rides the unchanged BLOCKED step ([DSG §4], E2).

Architecture decisions ([ARCH §Architecture decisions]) — consumed and
realized:

| AD | Consumed as |
|---|---|
| AD-1 reuse over parallel structure | §7's pass-through rows; the design's new surface is exactly C1's contracts, DS-1/DS-2, C-C3-1/C-C3-3, C-C4-2, C-C5-* |
| AD-2 the store is the only shared substrate | §8's compliance map; §5's store-mediated steps; the contract-visible no-edge rule |
| AD-3 the dial record is a convention, not schema | DS-1 grammar + C-C2-1's validation-free custody; §11's re-route keeps the promotion conditional |
| AD-4 horizon enforcement at the schedule lane | C-C3-3 (E6); in-flight-to-verdict; the digest names the horizon end |
| AD-5 the release trigger is outside the machine | C-C4-2 + DS-4 pending-by-absence; DENY-5 |
| AD-6 defaults are doctrine, not new policy | C-C5-1 consumed by C-C3-1's fallthrough; no new default invented anywhere |

## 10. Coverage: every architecture element resolves

| [ARCH] element | Resolves to |
|---|---|
| C1 intake | §2.1 contracts C-C1-1..C-C1-3 |
| C2 store | §2.2 contracts C-C2-1 (+ C-C2-2 consumed) |
| C3 pump | §2.3 contracts C-C3-1..C-C3-3 |
| C4 gates | §2.4 contracts C-C4-1 (consumed) + C-C4-2 |
| C5 doctrine | §2.5 contracts C-C5-1..C-C5-3 |
| E1..E6 | §8 compliance map (each edge's riding contracts/workflows) |
| the seven denied edges | §8 DENY-1..DENY-7 + the contract-visible no-edge rule paragraph |
| G-1 store-verifiable position | W-1 (+ DS-1, DS-2) |
| G-2 T-041 release floor | W-3 (+ DS-4; DENY-2/DENY-5) |
| G-3 T-039 non-blocking recurrence | W-3 step 2 + DS-3 |
| G-4 F-4 safe-end defaults | W-4 (+ DS-2 step 3, C-C5-1) |
| QA-1..QA-4 | §9 realization map |
| AD-1..AD-6 | §9 realization table (+ §11 for AD-3's conditional) |

Consistency with [SYS]: the two dials are named as [SYS §The two dials]
names them; the T-041 floor is a standing rule, not a dial value (DS-1
rule 1); the record's only home is the GOAL root's description with the
Q&A in a comment; children inherit via `basis` with no restatement
(DS-1/DS-2); NEEDS-HUMAN is the escalation threshold at every setting
(C-C3-2's BLOCKED step, unchanged); a time-boxed stop is deliberate,
not an escalation (C-C3-3). No contract contradicts [SYS].

## 11. Open questions: answered and re-routed

[ARCH §Open questions] records none at the architecture level and hands
down one inherited conditional item — promoting the dial record to
first-class store schema, "only if intake misses recur". Design's
answer:

- **Answered here — the grammar (DD-01).** The record's shape is now
  fixed (DS-1): closed vocabularies, one labeled line per dial, the
  open-questions line always present. This makes an intake miss
  detectable by reading the store (`task_get` shows either a parseable
  record or an explicit open question) without any schema change.
- **Re-routed with rationale — the schema promotion.** The trigger
  ("intake misses recur") has not fired; AD-3's trade-off stands (the
  store cannot validate presence or shape; the F-4 default read is the
  safety net). The promotion, if ever needed, is a **C2/store change —
  never a C3/pump change** ([SYS §Key trade-offs]): it would add
  first-class ticket fields behind the same E1 verbs, leaving every
  contract in §2 otherwise intact. No ticket is opened now — opening
  one would be the speculative machinery [SYS §Overview] forbids; the
  condition and the shape of the change are recorded here so the
  re-route is explicit rather than forgotten.

## 12. Acceptance criteria mapping (T-125)

- **AC-1** — design artifact exists with the docs/ About header;
  component contracts with inputs/outputs/error behavior named; workflows
  consistent with E1..E6 and the seven denials → this document: the
  About header above; §2 (all five components, every contract with all
  three faces); §5 (W-1..W-4, every step annotated with executor +
  edges); §8 (the compliance map — no denied edge used anywhere).
- **AC-2** — every architecture element resolves to a contract or an
  explicit pass-through; no contradiction with `docs/autonomy-spectrum-system.md`;
  the C1/C3/C4 no-edge rule contract-visible → §10 (the coverage table,
  all of C1..C5, E1..E6, the denials, G-1..G-4, QA-1..QA-4, AD-1..AD-6);
  §10's [SYS] consistency paragraph; §8's dedicated no-edge paragraph
  (repeated as boundary blocks inside §2.1, §2.2, §2.3, §2.4, §2.5).
- **AC-3** — the artifact is recorded on this ticket (doc) and the
  ticket is in-review; done is never self-assessed → store discipline:
  `task_add_artifact` (kind `doc`) before the `in-review` flip; the
  fulfillment comment names the stage skill (`software-design`); `done`
  remains the engine review pass's verdict.

## 13. Lane and non-goals

This ticket's lane is one new doc file
(`docs/autonomy-spectrum-design.md`). Not touched: the architecture and
system docs (read-only bases), `docs/design-stage-pipeline.md`,
`docs/acceptance-run-*.md` (read-only reuse bases), `.opencode/agent/product-owner.md`,
`AGENTS.md`, any skill, any code, any store file. No concrete provider
or model ids anywhere (D-004). The contracts are specified, not
scripted; component *realization* (landing the grammar into [PO]'s
procedure text, the steward glue, the doctrine entries) belongs to the
implementation stage; the contracts' verification belongs to the paired
`test-design` stage, whose criteria decide this artifact. Downstream
hand-off: implementation derives from §2/§4/§5; `test-design` tests
against the §2 contracts, §5 workflows and §8 compliance map.
