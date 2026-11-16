# Component criteria: the release-cadence design doc (U-113) — criteria & verdict (T-122)

## About this document

- **Kind:** `doc` / test-design verification artifact (V stage: test-design;
  ticket T-122, epic U-113, basis T-109). The PAIRED VERIFICATION of ticket
  **T-121**'s design artifact (sibling pair under T-109's division: T-121
  walks the design leg, T-122 this test-design leg). It derives component
  criteria **1:1 from T-121's acceptance criteria**, gives each an observable
  oracle — the design doc's own sections are the source of truth for *what
  the design decides*, the governing definitions are the source of truth for
  *whether it is correct and complete* — and records the verdict that decides
  T-121's done/not-done contribution (mirrored as a comment on ticket T-122;
  this file carries the criteria and the located evidence). This pair decides
  the design child only; it does not gate other waves. **Read by:** the
  engine review pass (weighs the verdict; alone sets `done`, [DSG §8]), the
  ticker-v stage ticker, the maintainer; **written by:** T-122 (test-design
  stage, discipline `test-software-design`).
- **Related:** the artifact under verification
  `docs/release-cadence-design.md` (T-121's product, recorded
  2026-11-16T08:34:07Z, in-review — read and cited here, never edited by this
  ticket); governing definitions `docs/release-cadence-architecture.md`
  (T-109's product — components C1..C6, edges E1..E9 + the denied-edge list,
  AD-001..AD-006, QA-1..QA-4; cited [ARCH]); `docs/release-cadence-flow.md`
  (T-093 — the system flow the "no contract contradicts" criterion checks
  against; cited [SYS]); `docs/design-stage-pipeline.md` (the tick machinery
  — the "no invented parallel structure" criterion's reference; cited [DSG]);
  `docs/live-in-eclipse-recurrence-flow.md` (the sibling system flow —
  composed with, referenced, never modified; cited [REC]); format reference
  `docs/acceptance-run-design.tests.md` (T-096 — the sibling method,
  read-only); tickets T-121 (under verification) and T-122 (this record).

## Scope: what "component level" verifies here

The release cadence is a **process/agent architecture** ([ARCH §Scope];
restate by the design, §1): its components C1..C6 are components of the run —
store verbs, the ticker's close sweep, the chat session, the release script,
the documents — so its component level is the design INSIDE each run surface,
not code. Layer separation against the already-walked levels:

- **Architecture level (done):** T-109
  (`docs/release-cadence-architecture.md`) fixed the boundaries — the
  component set, default-deny edges E1..E9, the nine-entry denied-edge list,
  AD-001..AD-006, QA-1..QA-4. This record does not re-derive them; it reads
  them as fixed structure.
- **Component level (this record):** per T-122's ticket description — the
  design artifact's contracts are complete and consistent: each component
  contract names inputs/outputs/error behavior; each workflow respects the
  architecture's dependency rules; the human-gate denial is contract-visible;
  no contract contradicts `docs/release-cadence-flow.md`; no invented
  parallel structure vs `docs/design-stage-pipeline.md`.

## Method and verdict semantics

Static documentation-level verification (the sibling method, T-096): every
criterion is decided by reading the design doc T-121 records — located by its
own sections — and checked against the governing section named in the oracle.
No store writes are needed beyond this record's own bookkeeping.

Unlike the concurrent T-095/T-096 pair (whose verdict was deferred until the
artifact landed), T-121's artifact EXISTS at verification time — so the
criteria are decided NOW: **PASS/FAIL per criterion** with the evidence
located or quoted; the overall verdict (**APPROVE / APPROVE-WITH-NOTES /
REJECT**) decides T-121's done/not-done contribution and is **advisory to
the engine review pass**, which alone sets `done` ([DSG §8]). T-122 sets no
status on T-121; a REJECT is a failed validation that blocks T-121 in its own
stage and escalates to its basis per the routing rule.

## Criterion derivation (1:1 from T-121's ACs)

| T-121 AC (verbatim concern) | Criteria |
|---|---|
| AC-1 "design artifact exists (docs/ convention About header) with component contracts for the architecture's components: inputs/outputs/error behavior named, workflows consistent with edges E1..E9 and the denied-edge rules" | CT-RC-001 (exists at the chain-convention path + About header), CT-RC-002 (per-component contracts complete, incl. the shared evaluation contract and the data structures), CT-RC-003 (workflows within E1..E9; denied edges enforced as absences) |
| AC-2 "every architecture element (C1..C6, actor rows, the 12-clause evidence oracle, both outcome paths, N1..N6) resolves to a contract or an explicit pass-through; no contract contradicts docs/release-cadence-flow.md; the C2->C4 human-gate denial is contract-visible" | CT-RC-004 (the §8 matrix resolves every element), CT-RC-005 (no [SYS] contradiction), CT-RC-006 (the denial contract-visible) |
| T-122 description (the pair contract, completing the derivation): "no invented parallel structure vs docs/design-stage-pipeline.md" | CT-RC-007 (pass-through where [DSG] fixes structure; zero re-decision markers) |
| AC-3 "the artifact is recorded on this ticket (doc) and the ticket is in-review; done is never self-assessed" | CT-RC-008 (T-121 history ordering — the artifact-before-flip proof) |

Every criterion below traces to exactly one AC concern (T-121's ACs, plus the
one clause the pair contract adds); no criterion verifies structure outside
T-121's ACs and the T-122 description.

## Component criteria

### CT-RC-001 · The design artifact exists at the chain-convention path with the repo doc header (T-121 AC-1)

- **Statement:** T-121 records exactly one stage-product artifact — the
  release-cadence design doc — and the doc carries the repo documentation
  header.
- **Oracle:** (a) the artifact recorded on T-121 (the store-side index per
  [DSG §7]) resolves to an existing Markdown file in the repo; (b) its path
  follows the chain naming convention: the release-cadence chain's stage
  products live at `docs/release-cadence-<stage>.md` (system:
  `docs/release-cadence-flow.md`; architecture:
  `docs/release-cadence-architecture.md`), so the design stage product is
  `docs/release-cadence-design.md`; (c) the file opens with the **About this
  document** header per the `project-manager-doc-about` convention — kind,
  read-by, related, and the owning ticket id recorded.
- **PASS:** all three observable. **FAIL:** no artifact on T-121 at decision
  time; the ref does not resolve; the About header missing; a path that
  breaks the chain convention without a recorded reason on the ticket.
- **Decided: PASS.** (a) T-121's artifact list carries exactly one entry —
  `doc: docs/release-cadence-design.md`, recorded 2026-11-16T08:34:07Z with
  the stage-product note — and the file resolves (read in full at
  verification time); (b) the chain convention holds (flow / architecture /
  design siblings); (c) the About header is present with kind, read-by,
  related, and the owning ticket (T-121, epic U-113, basis T-109, stage
  `design`).

### CT-RC-002 · Per-component contracts complete: inputs, outputs, error behavior — every component, the shared contract and the data structures included (T-121 AC-1)

- **Statement:** every component C1..C6 ([ARCH §Components and layers])
  carries at least one component contract in the design doc that names
  **inputs, outputs and error behavior** — or an EXPLICIT pass-through where
  the inside is already designed elsewhere (the design's §1 scope rule);
  the shared evaluation contract (C5's spec applied by C2 and C3) and the
  store-side data structures are designed.
- **Oracle:** locate the contracts in the design doc's §2; check each named
  contract for the three faces (a contract naming only the happy path is
  incomplete) and each pass-through for explicitness (a silent omission is
  not a pass-through); check §4 for the data structures. Consistency anchors:
  [ARCH]'s component rows and edge table; [SYS §3.4] (the query);
  [SYS §4.2] (D-002, "the flow adds no validation of its own"). A contract
  contradicting its component's basis row FAILS.
- **PASS:** C1..C6 all contracted or explicitly passed through, three faces
  present where a contract exists; the shared contract and the §4 structures
  present and consistent. **FAIL:** a component with neither contract nor
  explicit pass-through; a contract missing any face; a structure
  contradicting its basis.
- **Decided: PASS.** §2 covers all six: the shared evaluation contract (three
  contracts in `name(inputs) -> output | errors` notation, each with an
  errors line — `determineOutcome`'s "errors: none thrown — the conservative
  asymmetry (R4)"; `recordOutcome`'s instance-comment-write-failure
  behavior); **C1** — explicit pass-through, labeled as such, with the
  consumed read/write surface, relied-on invariants and the deliberately
  absent list; **C2** — `evaluateCompletedWaves() -> void` with its errors
  line ("none — every condition resolves to a valid Outcome; a sweep lost
  mid-run is re-derived"), the E1 write surface, the E7 knowledge surface,
  and the deliberately-absent block; **C3** — five contracts
  (`evaluateWaveFallback`, `reRecordOwnerSpeech`, `executeCut -> CutResult |
  CutFailure` with its three preconditions, `recordCut`, `recordCutFailure`),
  each with error behavior; **C4** — pass-through + the invocation contract
  `release.ps1 [-Push]` with the full Die list (repository, wrapper, format,
  tag-exists, branch, dirty tree, freshness, push) and the gate-failure
  revert; **C5** and **C6** — explicit pass-throughs, labeled. §4 defines all
  five data structures (`WaveRef`, `EvidenceMatch`, `EvidenceResult`,
  `OwnerGo`, `CutResult`/`CutFailure`) with invariants; §6 consolidates the
  error behavior in a 13-row table. Every pass-through is explicit (the word
  and the rationale) — none silent.

### CT-RC-003 · Every workflow stays within E1..E9; the denied edges stay enforced as absences (T-121 AC-1)

- **Statement:** the designed workflows cover both evaluations, both outcome
  paths and the later unlock; every interaction a workflow performs is
  performable through an allowed edge E1..E9; every denied edge appears as a
  contract ABSENCE, not as discipline ([ARCH QA-1]).
- **Oracle:** the design doc's §5 workflows (W-1..W-5) with their
  per-workflow edge citations, and §7's two tables, checked against [ARCH]'s
  edge table (E1..E9) and its nine-entry denied-edge list. Each §7
  "Realized by" row must name a §2 contract; each denial row must name the
  absence that enforces it. A workflow step performable only through a denied
  edge FAILS.
- **PASS:** both paths + the unlock designed; every step maps to a listed
  edge; no denied-edge step anywhere. **FAIL:** a scope-named workflow
  absent; a step requiring a denied edge; a denial row enforcing by
  discipline instead of absence.
- **Decided: PASS.** W-1 close-sweep evaluation (E1, E7; N1, N2, N5), W-2
  fallback evaluation (E2, E8; H7; R1), W-3 = Path A the cut (E9/E2, E1, E3,
  E5, E6; R5, N3, N4, N6), W-4 = Path B commit + push, park (N2, N6), W-5 =
  the unlock later (N5, QA-3) — both outcome paths and the unlock are
  designed workflows, as T-121's scope required. §7's realized-by table maps
  all nine edges E1..E9, each to a named §2 contract, matching [ARCH]'s edge
  table one-to-one (E1 C2→C1 through E9 owner→C1). §7's denial table carries
  all nine architecture denials, each enforced by a named contract absence
  (e.g. "C1 → anything | C1 has no contract at all"; "C4 → C1 | no store
  verb on C4's surface — results are returned to the caller"). No workflow
  step needs a denied edge: the cut is reachable only via W-3's C3
  `executeCut` (E3) on the owner's go; W-1's step 6 do-NOTs keep C2 off C4,
  blocking, spawning and escalation.

### CT-RC-004 · Every architecture element resolves to a contract or an explicit pass-through (T-121 AC-2)

- **Statement:** the design resolves every element [ARCH] names — C1..C6,
  all five actor rows, the 12-clause evidence oracle, both outcome paths plus
  the unlock, N1..N6 — with the consistency rule that each resolution cites
  the [SYS] section it preserves (the design's §8 preamble states the rule).
- **Oracle:** the design doc's §8 matrix. Count the element classes: six
  component rows; five actor rows; TWELVE oracle-clause rows (store-only;
  not status-transition history; not assignee fields; not git authorship;
  not chat text; identity conventions; the `chat-session` call-out; canonical
  instance evidence; secondary shapes; the 4-step query; the content floor;
  never-counts); the Path A / Path B / unlock rows; six N-rows. Each cell
  carries a design resolution citing the design's own sections and the [SYS]
  section preserved. A counted element with no row, or a row without a [SYS]
  citation, FAILS.
- **PASS:** all counted rows present, each resolved with citations.
  **FAIL:** a missing element row; a row citing nothing.
- **Decided: PASS.** All rows present and counted exactly: C1..C6 (6); actor
  rows (5 — stage ticker pass, kickoff assistant, PO/maintainer, engine
  review pass, PM/housekeeping as the denied evaluation edge); oracle clauses
  (12, enumerated above); Path A → W-3, Path B → W-4, the unlock → W-5;
  N1..N6 (6); plus rows beyond the required minimum (the [REC] seam rows,
  QA-1..QA-4, AD-001..AD-006). Every resolution cites both the design
  section and the [SYS] section it preserves.

### CT-RC-005 · No contract contradicts docs/release-cadence-flow.md (T-121 AC-2)

- **Statement:** every contract and workflow preserves the system flow;
  nothing re-decides what [SYS] fixed.
- **Oracle:** point-checks of the design's contracts against [SYS]'s own
  clauses, each located in both docs: the owner set (`owner`,
  `owner-directive`, `owner-acceptance`) [SYS §3.2 / §3.4 step 3]; the
  4-step query [SYS §3.4]; the content floor and the conservative asymmetry
  [SYS §3.4]; the digest-line format [SYS §4.1 step 3 / §4.3]; the four
  do-NOTs [SYS §4.1 step 4]; the never-counts rule [SYS §3.5]; the
  go / between-passes / derived-version preconditions and the D-002 sequence
  [SYS §4.2]; the two parking markers [SYS §4.3]; the flip and the cumulative
  cut [SYS §4.4]; the micro-record carve-out [SYS §1]; no nagging [SYS §2
  row 3 / §8]. A contract that contradicts any clause FAILS; a refinement
  that preserves the clause (recording semantics that keep the outcome state
  on the instance and every evaluation in the digest) PASSES.
- **PASS:** every point-check locates the clause in both docs, preserved.
  **FAIL:** any contradiction.
- **Decided: PASS.** Located pair-wise: the shared query is [SYS §3.4]'s four
  steps verbatim (design §2 opening); the digest-line template in W-1 step 5
  ("release cadence: wave \<record id\> UNLOCKED (evidence \<ticket id\> +
  author + ts)" / "… pending review") matches [SYS §4.1 step 3]'s wording;
  W-1 step 6 carries the four do-NOTs verbatim; `executeCut`'s three
  preconditions and the version-is-derived rule restate [SYS §4.2]; W-4
  parks on exactly [SYS §4.3]'s two markers ("nothing new is created"); W-5
  re-derives the flip marker-free per [SYS §4.4]; §2 C1's deliberately-absent
  list and §4's "No cadence-specific structure is ever STORED" preserve
  [SYS §1]'s zero-new-surfaces promise; the carve-out invariant sits in §4
  `WaveRef`; never-counts appear as §2 C3's own-writes-are-machine note and
  §2 C6's machine-class rule. The one apparent deviation — DS-002's
  instance-comment cadence (first determination + every flip, not every
  sweep) — is a refinement that PRESERVES [SYS]: the digest line still
  records every evaluation ([SYS §4.3]'s audit trail), the outcome state
  still lives on the instance, and flips are recorded ([SYS §4.4]). No
  contradiction found.

### CT-RC-006 · The C2→C4 human-gate denial is contract-visible (T-121 AC-2)

- **Statement:** the central denial — the evaluator never cuts — appears in
  the design as a missing contract on C2's surface, discoverable by a reader
  of the design doc alone.
- **Oracle:** the design doc states the denial as contract absence in three
  places (its §10 claims exactly these three): (1) §2 C2's "Deliberately
  absent" block naming "No `invokeCut` / `runRelease` / any execution verb —
  the human-gate denial (C2→C4)"; (2) §7's denied-edge table row "C2 → C4 —
  THE human-gate denial"; (3) §8's matrix row carrying the denial (N3). Plus
  the workflow half: W-1's step 6 "do NOT run `release.ps1`". Absence of any
  of the three FAILS.
- **PASS:** all three located and quoted; the workflow half present.
  **FAIL:** the denial stated only as discipline (a rule to follow, not a
  missing contract), or any of the three locations missing.
- **Decided: PASS.** All three located: §2 C2 ("**No `invokeCut` /
  `runRelease` / any execution verb — the human-gate denial (C2→C4)**, the
  central denial [ARCH] names … UNLOCKED determines and records — it never
  executes"); §7 ("| C2 → C4 — THE human-gate denial | NO execution verb on
  C2's surface (§2 C2, Deliberately absent; DS-003) — the evaluator can
  never become the cutter |"); §8's N3 row ("the denied C2→C4 +
  `executeCut`'s between-passes precondition + the dirty-tree guard"). W-1
  step 6 carries the workflow half. The same block carries the companion
  denials (no block/escalation verb, no owner-identity write, no spawn) —
  the absence-block is the enforcement surface, per QA-1.

### CT-RC-007 · Pass-through where [DSG] fixes the structure — no invented parallel structure (T-122 description)

- **Statement:** wherever the design relies on structure [DSG] already
  fixes, it records the mapping instead of re-deciding it; the cadence adds
  no parallel machinery.
- **Oracle:** both directions. (Positive) the design doc's §1 pass-through
  list cites [DSG §4] for the tick ("[DSG §4] is the machine's design; C2's
  evaluation attaches to it as the named extension point, and nothing here
  redesigns the tick") and [DSG §6] for C5; §2 C6 passes through [DSG §8]
  engine-only done. (Negative) a re-decision-marker sweep over the whole
  doc: a new tick step or a reordered tick; a link relation beyond
  `epic`/`basis`; a new status; a second `done` authority; a new ticket type
  or store concept the cadence mints; a second skill/copy mechanism; an
  artifact mechanism beyond the store index. Any marker FAILS.
- **PASS:** mappings recorded where reliance exists; zero re-decision
  markers. **FAIL:** any re-decision marker; described-but-not-mapped
  reliance on DSG-fixed structure.
- **Decided: PASS.** Positive: §1's pass-through list is explicit (C1's
  engine-owned store contracts; C4 verbatim; C5's wording consumed verbatim;
  the tick attached as "the named extension point"); §2 C5/C6 cite
  [DSG §6]/[DSG §8]. Negative sweep clean: the evaluation attaches at tick
  step 6 and adds no step, reorders none (W-1 step 1); the cadence mints no
  tickets of its own (W-1's seams summary; §2 C1's "no cadence ticket type");
  §4 stores no cadence structure; `done` stays engine-only (§2 C6); recording
  rides the store index conventions (`task_add_comment`, `task_add_artifact`,
  the digest-line convention of U-061) — no second mechanism; DS-001..DS-006
  decide none of the DSG-fixed structure (they are recording-semantic and
  denial decisions inside C2/C3/C4's own surfaces).

### CT-RC-008 · T-121 recorded its artifact before the in-review flip, and no done is self-assessed (T-121 AC-3)

- **Statement:** the fulfillment order holds on the ticket record — the
  store-side proof of the hand-off discipline for this pair.
- **Oracle:** T-121's history, read live at verification time: an
  `artifact:…` event carrying the design doc ref, timestamped before the
  `updated:status` flip to `in-review`; the status after the flip is
  `in-review` (never `done` by the worker's own hand — [DSG §8] engine-only
  done); no `done` event in the history. Flip before artifact, or any done
  event, FAILS.
- **PASS:** artifact event precedes the flip; window ends at in-review.
  **FAIL:** flip before artifact; any `done` inside the window.
- **Decided: PASS.** Read via `task_get(T-121)` at verification time
  (2026-11-16): history shows `2026-11-16T08:34:07.064Z
  artifact:doc:docs/release-cadence-design.md` (with the stage-product note)
  followed by `2026-11-16T08:34:16.683Z updated:status` (→ `in-review`);
  status is `in-review`; no `done` event exists. Artifact precedes the flip
  by ~9 seconds.

## Verdict

**APPROVE** — all eight criteria PASS with located evidence
(CT-RC-001..CT-RC-008 above). Non-blocking observations: none — the checks
found no gaps and no contradictions; the one refinement noted at CT-RC-005
(DS-002's recording cadence) preserves its [SYS] clauses and is recorded as
such, not as a finding.

Per the routing rule: this verdict decides T-121's done/not-done contribution
in the reviewer's hands and is advisory to the engine review pass, which
alone sets `done` ([DSG §8]); T-122 sets no status on T-121. The verdict is
mirrored as a comment on ticket T-122; this file carries the criteria and the
evidence.

## Lane

This ticket's lane is one new doc file (`docs/release-cadence-design.tests.md`).
Not touched: `docs/release-cadence-design.md` (the sibling's lane — read,
cited, never edited), the governing docs ([ARCH] / [SYS] / [DSG] / [REC]),
any code, any skill. No concrete provider or model ids anywhere (D-004).
