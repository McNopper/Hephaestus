# Component criteria: the live-in-Eclipse recurrence design doc (T-119) — criteria & verdict (T-120)

## About this document

- **Kind:** `doc` / test-design verification artifact (V stage: test-design;
  ticket T-120, epic U-113, basis T-107). The PAIRED VERIFICATION of ticket
  **T-119**'s design artifact: it derives component criteria **1:1 from
  T-119's acceptance criteria**, gives each an observable oracle — the design
  doc's own sections are the source of truth for *what the design decides*,
  the governing definitions are the source of truth for *whether it is
  correct and complete* — and records the verdict that decides T-119's
  done/not-done contribution (mirrored as a comment on ticket T-120; this
  file carries the criteria and the evidence). This pair decides the design
  child's done/not-done; it does not gate other waves.
- **Read by:** the engine review pass (weighs the verdict; alone sets
  `done`), the ticker-v stage ticker, the maintainer; **written by:** T-120
  (test-design stage, discipline `test-software-design`).
- **Related:** the artifact under verification
  `docs/live-in-eclipse-recurrence-design.md` (T-119 — LANDED at decision
  time, recorded 2026-11-16T08:35:05Z; cited [DES]); governing definitions
  `docs/live-in-eclipse-recurrence-architecture.md` (T-107's product —
  components C-1..C-5, the §3 instance contract, DR-1..DR-10, QA-001..004,
  AD-001..006; cited [ARCH]);
  `docs/live-in-eclipse-recurrence-flow.md` (T-085 — the system flow, the
  no-contradiction basis; cited [SYS]);
  `docs/release-cadence-flow.md` (T-093 — the composition seam; cited
  [CAD]); `docs/design-stage-pipeline.md` (the U-110 tick machinery — the
  no-invented-structure basis; cited [DSG]);
  `.opencode/skills/stage-pipeline/SKILL.md` (the live tick steps — the
  reality anchors; cited [SKILL]); sibling records
  `docs/acceptance-run-design.tests.md` (the format and method precedent)
  and `docs/live-in-eclipse-recurrence-flow.tests.md`; tickets T-119 (under
  verification) and T-120 (this record's ticket).
- **Currency (T-202, release v0.4.5):** the sprint model is removed -
  plan/close-sprint steps, sprint records, the product-backlog /
  sprint-backlog statuses and the wave instance (R2) are gone; the store
  is the seven-state model (born ready/paused, claims ready-only). The
  sprint-record and R2-mint mechanics observed below are the run-date
  record - their verdicts stand as recorded on their dates
  (re-verified 2026-11-26).

## Scope: what "component level" verifies here

The recurrence's components are modules of a **process/agent architecture**
([ARCH §1] — peers over one store) anchored in store, skill, schedule and
chat surfaces — so its component level is the design INSIDE each module
(contracts, data structures, workflows, error behavior), not code. Layer
separation against the already-verified level:

- **Architecture level (done):** T-107's artifact ([ARCH]) fixed the
  boundaries — five components with responsibilities, ten dependency rules,
  the instance contract, six guarantees realized structurally — and was
  verified by its own paired ticket. This record does not re-verify
  boundaries; it reads them as fixed structure.
- **Component level (this record):** per T-120's own ticket description —
  "the design artifact's contracts are complete and consistent - each
  component contract names inputs/outputs/error behavior; each workflow
  respects the architecture's dependency rules; no contract contradicts
  docs/live-in-eclipse-recurrence-flow.md; no invented parallel structure vs
  docs/design-stage-pipeline.md". T-119's scope (ticket description) names
  the substance: component contracts for C-1..C-5, the key workflows
  (W-1..W-6), the minted-ticket data contract refined to field level, and
  R1/R2/R3, the actor rows and G1..G6 resolved to contracts or explicit
  pass-throughs.

Unlike the acceptance-run pair (T-095/T-096, concurrent at minting time),
this pair ran CONSECUTIVELY: T-119's artifact landed and T-119 sits
`in-review` at decision time, so every criterion below is DECIDED here with
the located evidence recorded inline.

## Method and verdict semantics

Static documentation-level verification (the sibling method, per the
`test-software-design` skill): every criterion is decided by reading the
design doc T-119 records — located by its own sections — and checked against
the governing section named in the oracle. No store writes are needed beyond
this record's own bookkeeping. Verdict semantics: **PASS/FAIL per criterion**
with the evidence located or quoted; the overall verdict (**APPROVE /
APPROVE-WITH-NOTES / REJECT**) decides T-119's done/not-done contribution
and is **advisory to the engine review pass**, which alone sets `done`.
T-120 sets no status on T-119; a REJECT is a failed validation that blocks
T-119 in its own stage and escalates to its basis T-107 per the routing
rule.

## Criterion derivation (1:1 from T-119's ACs)

| T-119 AC (verbatim concern) | Criteria |
|---|---|
| AC-1 "design artifact exists (docs/ convention About header) with component contracts for the architecture's components: inputs/outputs/error behavior named, workflows consistent with the architecture's dependency rules DR-1..DR-10" | CT-RD-001 (exists at the chain-convention path + About header), CT-RD-002 (per-component contracts complete, data structures included), CT-RD-003 (workflows respect DR-1..DR-10) |
| AC-2 "every architecture element (C-1..C-5, R1/R2/R3, actor rows, G1..G6, the minted-ticket data contract) resolves to a component contract or an explicit pass-through; no contract contradicts docs/live-in-eclipse-recurrence-flow.md; composition with docs/release-cadence-flow.md preserved" | CT-RD-004 (traceability: every element resolves, the data contract at field level), CT-RD-005 (no contradiction with the flow), CT-RD-006 (composition preserved read-only) |
| T-120 description concern: "no invented parallel structure vs docs/design-stage-pipeline.md" (T-119's basis list carries [DSG] for the same reason) | CT-RD-007 (pass-through recorded; zero re-decision markers) |
| AC-3 "the artifact is recorded on this ticket (doc) and the ticket is in-review; done is never self-assessed" | CT-RD-008 (T-119 history ordering — the fulfillment-order pattern) |

Every criterion below traces to exactly one AC concern; no criterion
verifies structure outside T-119's ACs and T-120's ticket description.

## Component criteria

### CT-RD-001 · The design artifact exists at the chain-convention path with the repo doc header (T-119 AC-1)

- **Statement:** T-119 records exactly one stage-product artifact — the
  recurrence design doc — and the doc carries the repo documentation header.
- **Oracle:** (a) the artifact recorded on T-119 (the store-side index per
  [DSG §7]) resolves to an existing Markdown file in the repo; (b) its path
  follows the chain naming convention: the recurrence chain's stage products
  live at `docs/live-in-eclipse-recurrence-<stage>.md` (flow:
  `docs/live-in-eclipse-recurrence-flow.md`, architecture:
  `docs/live-in-eclipse-recurrence-architecture.md`), so the design stage
  product is `docs/live-in-eclipse-recurrence-design.md`; (c) the file
  opens with the **About this document** header per the
  `project-manager-doc-about` convention — kind, read-by, written-by,
  related, and the owning ticket id recorded.
- **PASS:** all three observable.
- **FAIL:** no artifact on T-119 at decision time; the ref does not
  resolve; the About header missing; a path that breaks the chain
  convention without a recorded reason on the ticket.
- **Evidence (decided 2026-11-16):** (a) T-119's artifact list carries
  `doc: docs/live-in-eclipse-recurrence-design.md` (history event
  2026-11-16T08:35:05.480Z) and the file exists in the tree (read in full
  at decision time). (b) The path completes the chain convention. (c) The
  header is present with kind ("design-stage artifact of ticket T-119 …
  per the `software-design` skill"), read-by, written-by, related, and the
  owning ticket id. **PASS.**

### CT-RD-002 · Per-component contracts complete: inputs, outputs, error behavior — every component, data structures included (T-119 AC-1)

- **Statement:** every component C-1..C-5 ([ARCH §2]) carries at least one
  component contract in the design doc that names its **inputs, outputs and
  error behavior** — an explicit pass-through counts where [ARCH] fixes the
  component as substrate; and the data structures the scope names (the
  instance contract at field level) are designed.
- **Oracle:** locate the contracts in [DES §2] (notation:
  `name(inputs) -> output | errors`); check each for the three faces (a
  contract naming only the happy path is incomplete); check C-3's
  resolution is the recorded pass-through with its consumed surfaces named;
  check [DES §4]'s `InstanceSpec` field table plus `SourceToken`,
  `WaveCandidate`, `MintOutcome`, `LiveEvidence`; check consistency against
  [ARCH §2] responsibilities, [ARCH §3] field values and [ARCH §6]
  AD-001..AD-006. A contract contradicting its component's basis row or the
  instance contract FAILS.
- **PASS:** all five components contracted (or explicitly passed through)
  with all three faces; the data structures designed consistently.
- **FAIL:** a component with no contract and no recorded pass-through; a
  contract missing any face; a contract contradicting its basis row.
- **Evidence (decided 2026-11-16):** C-1 carries six named contracts plus
  `isCompletedWave` ([DES §2 C-1]), each with an error face
  (`detectLiveChecks` → empty → stop; `dedupInstanceExists` → scan surface
  unavailable → deferred mint, never blind; `mintInstance` → refused
  create, refused update with retry-then-fallback-landing, and the
  repair-not-recreate rule); C-2 carries `fallbackMint` and
  `releaseBackfillMint` with the route-collision and template-refusal
  errors ([DES §2 C-2]); C-3 is the explicit pass-through, naming seven
  consumed `task_*` surfaces ([DES §2 C-3] — matches [ARCH §2 C-3]
  substrate); C-4 carries four outcome-shaped contracts (the human
  executor; the store is the proof) with the owner-absent and
  un-runnable-check error rows; C-5 carries `verdictInstance` plus the
  scope rule, with the missing-evidence FAIL ([DES §2 C-5] — AD-006
  honored). [DES §4] carries every [ARCH §3] field value 1:1 and adds only
  what the design leg owns (the release-backfill column, call-level
  invariants, the payload types — `MintOutcome.confirmed` is read-back
  truth; the `SourceToken` grammar is [SYS §3.4] verbatim). **PASS.**

### CT-RD-003 · Every workflow respects the architecture's dependency rules DR-1..DR-10 (T-119 AC-1)

- **Statement:** each designed workflow names its components and stays
  within DR-1..DR-10; no step requires a dependency direction the
  architecture denies.
- **Oracle:** [DES §5] workflows W-1..W-6, each checked against its own
  "Upheld" citations and against [DES §7]'s DR-compliance map, which must
  carry a row for every rule of [ARCH §4]; then the negative check per
  workflow: no component-to-component call (DR-1), no new trigger machinery
  (DR-2), no instance planned/claimed/spawned (DR-3), no derivation FROM an
  instance (DR-4), no second `done` authority (DR-5), no completion
  redefinition (DR-6), no release-path write (DR-7), no backward gating
  (DR-8), no cadence-state write (DR-9), no committed provider/model id
  (DR-10).
- **PASS:** every workflow's steps are performable through the allowed
  directions only; the compliance map is total.
- **FAIL:** a workflow step on a denied direction; a rule with no row.
- **Evidence (decided 2026-11-16):** W-1..W-6 each end with their "Upheld"
  DR citations ([DES §5]); [DES §7] has a row for every rule DR-1..DR-10.
  Spot-checks: W-1's steps touch only `task_*` surfaces (DR-1) and carry
  the never-plan/never-spawn never-clauses (DR-3); W-2's fixed order
  digest → sweep → cadence evaluation → close matches [SKILL] step 6 and
  [CAD §4.1]'s ordering (DR-6/DR-9); no workflow anywhere names a
  release-cut mint — the negative resolution of R3 is recorded as such
  (DR-7); W-6 blocks only the instance, never the done basis (DR-8); no
  workflow names a component-to-component call — each hops through the
  store (DR-1). **PASS.**

### CT-RD-004 · Every architecture element resolves to a contract or an explicit pass-through — the data contract at field level (T-119 AC-2)

- **Statement:** every element of the governing architecture — C-1..C-5,
  R1/R2/R3, the four actor rows and the pseudo-actor, G1..G6, the
  minted-ticket data contract — resolves in the design doc to a component
  contract, a designed structure, or an explicit pass-through; the data
  contract is refined to field level where the design leg owns it.
- **Oracle:** [DES §8]'s traceability matrix, checked element-by-element
  against [ARCH §2] (C-1..C-5), §4 (R1/R2/R3 as the DR-2/DR-7 hooks),
  §9's element classes (actor rows, the pseudo-actor, G1..G6, convergence),
  §3 (the data contract → [DES §4]), §5 (QA-001..QA-004) and §7 (the
  composition seam); plus the flow's invariants I1..I7 ([SYS §8]) resolve
  through the design's own citations (I1: [DES §2 C-2] and W-3; I2/I3: the
  DR-3 row; I4: the DR-4 row; I5: the G-rows collectively; I6: [DES §2 C-1]
  and the [DES §4] `SourceToken` invariant; I7: [DES §2 C-4] and the [DES
  §4] `LiveEvidence` floor).
- **PASS:** every element locates a resolution; nothing silently
  un-designed.
- **FAIL:** an element with no row and no resolving citation; a resolution
  that contradicts the element's basis.
- **Evidence (decided 2026-11-16):** every [ARCH] element has a [DES §8]
  row with a located resolution: C-1..C-5 → the §2 contracts (C-3 the
  recorded pass-through); R1/R2/R3 → `pairingMint`, `sweepCompletedWaves`,
  and the negative resolution plus `releaseBackfillMint`; actor rows 1–4 →
  the owning contracts and never-clauses; the pseudo-actor → [DES §4]'s
  status+assignee row; G1..G6 → the DR rows, the landing zones, W-2's fixed
  order, W-6, C-5's scope rule; the data contract → [DES §4]'s field table
  (values 1:1 plus the release column); QA-002/003/004 → DS-002/DS-004,
  DS-003, W-4 step 2; the seam → W-2 + the DR-9 row. QA-001 resolves
  through the G1..G6 rows (it IS those guarantees, [ARCH §5]); I1..I7
  resolve through the citations enumerated in the oracle — all located.
  **PASS.**

### CT-RD-005 · No contract contradicts docs/live-in-eclipse-recurrence-flow.md (T-119 AC-2)

- **Statement:** every value and step the design decides is either carried
  1:1 from the flow or a refinement that adds precision without changing a
  flow decision.
- **Oracle:** field-by-field: [DES §4] vs [SYS §3.1]; step-by-step: [DES
  §5 W-1/W-2] vs [SYS §5.1]'s two extension wordings; behavior: W-4/W-5/W-6
  vs [SYS §5.2–§5.4]; guarantees: the G1..G6 resolutions vs [SYS §6];
  error behavior: [DES §6]'s consolidated table vs [SYS §9]'s accepted
  trade-offs; template: rewording forbidden ([SYS §3.2/§3.3] carried by
  reference; AD-005).
- **PASS:** zero contradictions; every deviation is a recorded refinement,
  not a re-decision.
- **FAIL:** any field value, step order or behavior that contradicts the
  flow; a reworded template.
- **Evidence (decided 2026-11-16):** every [DES §4] field value equals
  [SYS §3.1] verbatim (title shapes, `task`/0/medium,
  `tester`/`test-requirements`, the epic rules, `basis` D/null, no sprint,
  `in-progress` + `owner-acceptance`, `model` null); W-1's six steps apply
  [SYS §5.1]'s STEP 4 EXTENSION step-for-step (scan → first-match token →
  dedup open-or-done → create+update → never plan/spawn); W-2 matches STEP
  6 EXTENSION including the per-mint digest note; W-6 is [SYS §5.4]
  (findings ride the NEXT wave; failed evidence recorded first; the blocker
  names the findings; only the owner clears); the DS-001 doubtful-line
  asymmetry mirrors [SYS §9]'s false-trigger precedent (a spurious instance
  is harmless, a skipped pairing is never re-derived); nothing rewords the
  template — the description and acceptance shapes are carried by reference
  with the variation points named. **PASS.**

### CT-RD-006 · Composition with docs/release-cadence-flow.md preserved (T-119 AC-2)

- **Statement:** the design composes with the sibling cadence walk
  read-only: the mint precedes the cadence evaluation, no contract writes
  cadence state, neither governing doc is modified.
- **Oracle:** [DES §5 W-2] steps 2–3 and [DES §7]'s DR-9 row vs [CAD
  §4.1] (the evaluation lives in the same step-6 sweep, ordered AFTER the
  sibling's R2 mint so the freshly minted instance is visible) and [CAD
  §6] (composition as one ruleset); the assignee citation in [DES §4] vs
  [CAD §3.1]; the lane check: [CAD] unmodified by T-119.
- **PASS:** the seam order and read-only direction hold; the cited
  composition sections resolve; the sibling doc is untouched.
- **FAIL:** a contract that writes cadence state, reorders the sweep, or
  modifies the sibling doc.
- **Evidence (decided 2026-11-16):** W-2's order (mint → "the cadence
  evaluation runs (it READS the instances the sweep created or kept)") is
  exactly [CAD §4.1]'s ordering rule ("ordered AFTER the sibling's R2
  mint"); the DR-9 row records the read-only seam ("no contract writes
  cadence state; neither governing doc modified"); [DES §4]'s "the
  assignee is a routing marker, never evidence (release-cadence §3.1)"
  resolves verbatim to [CAD §3.1] ("a routing marker ('awaits the owner'),
  never a record of the owner having acted"); [CAD §6]'s composition claims
  are upheld — this design adds no cadence ticket and no cadence write.
  [CAD] is not in T-119's lane and is unmodified (git working tree clean
  of changes to it at decision time). **PASS.**

### CT-RD-007 · Pass-through recorded where [DSG] fixes the structure — no invented parallel structure (T-120 description)

- **Statement:** wherever the design relies on structure the tick machinery
  already fixes, it records the mapping instead of re-deciding it; and the
  extension points it hooks exist in the live tick steps.
- **Oracle:** (positive) [DES §1]'s pass-through record — the tick
  machinery "[DSG §4–§6] is the design; the recurrence hooks the two
  extension points (DR-2) and adds no step, no timer, no eventing" — and
  the reality anchors ([SKILL] step 4 division with the live-in-Eclipse
  rule, step 6 close with the digest, step 3 verdict) existing as cited.
  (negative) a re-decision-marker sweep over the whole doc: a new tick step
  or a reordered algorithm ([DSG §4]); a new status, store concept or link
  relation beyond `epic`/`basis` ([DSG §2]/[DSG §3]); a second `done`
  authority; per-stage skill copies ([DSG §6]); a timer or event
  subscription; ticket fields outside the store schema.
- **PASS:** mappings recorded where reliance exists; zero re-decision
  markers; the anchors real.
- **FAIL:** any re-decision marker; a hook point that does not exist in
  [SKILL]; described-but-not-mapped reliance on [DSG]-fixed structure.
- **Evidence (decided 2026-11-16):** the anchors verified in [SKILL] (read
  at decision time): step 3 is IN-REVIEW → VERDICT (one read-only reviewer
  subagent per `in-review` ticket — C-5's anchor); step 4 is DONE →
  division and carries the live-in-Eclipse rule verbatim (C-1's R1 anchor);
  step 6 is CLOSE with the digest and U-108 reopen semantics (C-1's R2
  anchor); C-5's anchor additionally names [DSG §8] for the quality gate.
  The negative sweep is clean: the design's landing zones are existing
  states (`in-progress` + assignee, fallback `sprint-backlog` + assignee),
  the digest note is U-061's existing mechanism, the evidence artifact is
  the existing `task_add_artifact`, `epic`/`basis` keep their [DSG §3]
  roles, the verdict is the existing step-3 reviewer (no second `done`
  authority), and no timer, eventing or new store concept appears anywhere
  in [DES]. **PASS.**

### CT-RD-008 · T-119 recorded its artifact before the in-review flip; done never self-assessed (T-119 AC-3)

- **Statement:** the fulfillment order holds on the ticket record — the
  store-side proof of the worker discipline for this pair.
- **Oracle:** T-119's history: an `artifact:…` event carrying the design
  doc ref, timestamped **before** the `updated:status` → in-review flip;
  no `done` event inside the fulfillment window (`done` is the engine's
  verdict alone — [DES §2 C-5], DR-5).
- **PASS:** artifact event precedes the flip; the window ends at
  in-review.
- **FAIL:** flip before artifact; any `done` inside the window.
- **Evidence (decided 2026-11-16):** T-119's history read via `task_get`:
  `artifact:doc:docs/live-in-eclipse-recurrence-design.md` at
  2026-11-16T08:35:05.480Z, then `updated:status` at
  2026-11-16T08:35:20.656Z — the artifact precedes the flip; the artifact
  note names the stage product's content (contracts, data structures,
  workflows, error behavior, DR-compliance map, traceability matrix); the
  window ends at `in-review` (T-119's status at decision time); no `done`
  event exists. **PASS.**

## Verdict

**Decision protocol:** run CT-RD-001 → CT-RD-008 in order against the
recorded design doc; decide each by its oracle and record the located
evidence; overall verdict = **APPROVE** if all eight PASS,
**APPROVE-WITH-NOTES** if all PASS with non-blocking observations,
**REJECT** if any FAIL (failed validation → block T-119 in its own stage
with the failing criterion, escalate to the basis T-107). The verdict
decides T-119's done/not-done contribution in the reviewer's hands; the
engine review pass alone sets `done`; T-120 never sets a status on T-119.

**Result (2026-11-16):** all eight criteria PASS — **APPROVE**. Evidence:
CT-RD-001..CT-RD-008 above, each decided against the landed artifact
([DES], recorded on T-119 at 2026-11-16T08:35:05Z, in-review since
2026-11-16T08:35:20Z) with its oracle. No blocking or non-blocking
observations remain: the spot-checked citations ([DES §3]→[ARCH §1]/AD-001,
[DES §4]→[SYS §3.1]/[CAD §3.1], [DES §5 W-2]→[CAD §4.1]/§6, [DES §1]→[DSG
§4–§6]/[SKILL] steps 3/4/6) all resolve to sections that carry exactly the
claimed content.

## Lane and non-goals

This ticket's lane is one new doc file
(`docs/live-in-eclipse-recurrence-design.tests.md`). Not touched: the
artifact under verification (`docs/live-in-eclipse-recurrence-design.md` —
T-119's lane), the governing docs ([ARCH], [SYS], [CAD], [DSG]),
`.opencode/skills/stage-pipeline/`, `AGENTS.md`, `docs/TESTING.md`, any
code, any store data beyond this ticket's own bookkeeping. This record
verifies the design doc only; it does not verify an implementation (none
exists yet — when the pipeline divides implementation children, their
contracts' verification derives from [DES §2]/[DES §6] per the design's
own §10). No concrete provider or model ids anywhere (DR-10/D-004).
